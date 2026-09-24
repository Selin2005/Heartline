package com.heartline.phone.data

import com.heartline.shared.ecg.EcgFilter
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** A stored record with its decoded summary. */
data class StoredRecord(val entity: RecordEntity, val summary: RecordSummary) {
    val id get() = entity.id
}

class RecordRepository(
    private val dao: RecordDao,
    private val waves: WaveStore,
    private val now: () -> Long = System::currentTimeMillis,
) : RecordSink {
    fun observe(kind: RecordKind): Flow<List<StoredRecord>> = dao.observe(kind).map { list -> list.map { it.decode() } }

    fun observe(id: String): Flow<StoredRecord?> = dao.observeById(id).map { it?.decode() }

    suspend fun wave(record: StoredRecord): FloatArray? =
        record.entity.wavePath?.let { path -> withContext(Dispatchers.IO) { waves.read(path) } }

    /** The waveform filtered for display (the watch stores raw sensor values). */
    suspend fun displayWave(record: StoredRecord): FloatArray? = wave(record)?.let { raw ->
        withContext(Dispatchers.Default) {
            if (record.entity.kind == RecordKind.ECG) EcgFilter.clean(raw, record.entity.sampleRateHz) else raw
        }
    }

    override suspend fun contains(id: String) = dao.exists(id)

    override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
        val path = wave?.let { withContext(Dispatchers.IO) { waves.write(meta.id, meta.kind.ordinal, meta.sampleRateHz, it) } }
        dao.insert(
            RecordEntity(
                id = meta.id,
                kind = meta.kind,
                startedAtMs = meta.startedAtMs,
                durationMs = meta.durationMs,
                sampleRateHz = meta.sampleRateHz,
                sampleCount = meta.sampleCount,
                summaryJson = Protocol.json.encodeToString<RecordSummary>(meta.summary),
                wavePath = path,
                receivedAtMs = now(),
            ),
        )
    }

    suspend fun updateEcgSymptoms(id: String, symptoms: List<Symptom>, note: String?) {
        val entity = dao.get(id) ?: return
        val summary = entity.decode().summary as? RecordSummary.Ecg ?: return
        dao.updateSummary(id, Protocol.json.encodeToString<RecordSummary>(summary.copy(symptoms = symptoms)), note)
    }

    override suspend fun delete(id: String) {
        dao.get(id)?.wavePath?.let { withContext(Dispatchers.IO) { waves.delete(it) } }
        dao.delete(id)
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) { waves.deleteAll() }
        dao.deleteAll()
    }

    suspend fun isEmpty() = dao.count() == 0

    suspend fun all(): List<RecordEntity> = dao.all()

    private fun RecordEntity.decode() = StoredRecord(this, Protocol.json.decodeFromString<RecordSummary>(summaryJson))
}
