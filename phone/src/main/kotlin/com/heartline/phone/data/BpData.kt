// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import android.util.Log
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpDataset
import com.heartline.shared.bp.BpDatasetEntry
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HybridBpModel
import com.heartline.shared.bp.PpgEmbedder
import com.heartline.shared.bp.MorphologyEmbedder
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.RecordKind
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Entity(tableName = "bp_calibrations")
data class BpCalibrationEntity(@PrimaryKey val id: String, val createdAtMs: Long, val json: String)

/** A watch reading compared with a cuff reading taken right after it (validation mode). */
@Entity(tableName = "bp_validations")
data class BpValidationEntity(
    @PrimaryKey val id: String,
    val readingId: String,
    val atMs: Long,
    val watchSystolic: Int,
    val watchDiastolic: Int,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
)

@Dao
interface BpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(calibration: BpCalibrationEntity)

    @Query("SELECT * FROM bp_calibrations ORDER BY createdAtMs DESC LIMIT 1")
    fun latest(): Flow<BpCalibrationEntity?>

    @Query("DELETE FROM bp_calibrations")
    suspend fun deleteAll()

    @Query("DELETE FROM bp_calibrations WHERE id LIKE 'demo%'")
    suspend fun deleteDemo(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValidation(validation: BpValidationEntity)

    @Query("SELECT * FROM bp_validations ORDER BY atMs DESC")
    fun validations(): Flow<List<BpValidationEntity>>

    @Query("DELETE FROM bp_validations")
    suspend fun deleteValidations()
}

/**
 * Blood-pressure calibration (source of truth on the phone) and readings from the watch.
 *
 * Algorithm 3 on the phone:
 * - a cuff check ("compare with cuff") also becomes a calibration point, so the calibration
 *   learns the user's range and follows a drifting baseline, and is sent back to the watch;
 * - each reading that arrives with its pulse wave may be refined by the personal learned model
 *   ([HybridBpModel]) once that model beats the classical estimate on this user's own checks;
 * - a confirmed very high or low reading raises a notification ([onSafety]).
 */
class BpRepository(
    private val dao: BpDao,
    private val records: RecordRepository,
    private val onSafety: (RecordSummary.BloodPressure) -> Unit = {},
    /** Candidate encoders for the personal model; the one with the lowest leave-one-out error on this user's checks wins. */
    private val embedders: () -> List<PpgEmbedder> = { listOf(MorphologyEmbedder) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the watch's raw session logs are kept (algorithm 6); null keeps none. */
    private val sessionsDir: java.io.File? = null,
    private val sync: () -> PhoneSyncEngine,
) {
    private val captures = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 8)

    /** Calibration rounds recorded on the watch, as they arrive. */
    val captureResults: SharedFlow<CaptureResult> = captures.asSharedFlow()

    val calibration: Flow<BpCalibration?> = dao.latest().map { it?.let { e -> Protocol.json.decodeFromString<BpCalibration>(e.json) } }

    val readings: Flow<List<StoredRecord>> = records.observe(RecordKind.BLOOD_PRESSURE)

    suspend fun onCaptureResult(result: CaptureResult) = captures.emit(result)

    suspend fun requestCapture(request: CaptureRequest) = sync().requestCapture(request)

    suspend fun saveCalibration(calibration: BpCalibration) {
        dao.insert(BpCalibrationEntity(calibration.id, calibration.createdAtMs, Protocol.json.encodeToString(calibration)))
        sync().sendCalibration(calibration)
    }

    /**
     * Re-sends the active calibration (after the watch says hello); [orNull] also tells the watch to
     * drop its copy when there is none. An older calibration is first upgraded to the current
     * features from its stored raw PPG.
     */
    suspend fun resendCalibration(orNull: Boolean = false) {
        val current = calibration.first()?.let { upgradeIfNeeded(it) }
        if (current != null || orNull) sync().sendCalibration(current)
    }

    /** Stores the health profile with the current calibration and sends it to the watch. */
    suspend fun saveProfile(profile: BpProfile) {
        val cal = calibration.first() ?: return
        if (cal.profile != profile) saveCalibration(cal.copy(profile = profile))
    }

    private suspend fun upgradeIfNeeded(cal: BpCalibration): BpCalibration {
        val up = withContext(Dispatchers.Default) { cal.upgraded() }
        if (up != cal) dao.insert(BpCalibrationEntity(up.id, up.createdAtMs, Protocol.json.encodeToString(up)))
        return up
    }

    val validations: Flow<List<BpValidationEntity>> = dao.validations()

    /** Keeps a raw session log from the watch (every sensor of one measurement or calibration round). */
    suspend fun saveSession(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val dir = sessionsDir ?: return@withContext
        // Only a well-formed log is kept.
        runCatching { BpSessionLog.decode(bytes) }.onFailure { Log.w(TAG, "bad session $id", it) }.getOrNull() ?: return@withContext
        java.io.File(dir.apply { mkdirs() }, "$id.hlbp").writeBytes(bytes)
    }

    suspend fun session(id: String): BpSessionLog? = withContext(Dispatchers.IO) {
        sessionsDir?.let { java.io.File(it, "$id.hlbp") }?.takeIf { it.exists() }?.let { runCatching { BpSessionLog.decode(it.readBytes()) }.getOrNull() }
    }

    /**
     * Every raw session log, the calibration and the cuff-checked dataset in one zip, for
     * developing the algorithm on real data (tools/bp-ml/read_session.py reads it).
     */
    suspend fun exportSessions(target: java.io.File): java.io.File = withContext(Dispatchers.IO) {
        val data = dataset()
        java.util.zip.ZipOutputStream(target.apply { parentFile?.mkdirs() }.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            data?.let { put("dataset.json", Protocol.json.encodeToString(it).encodeToByteArray()) }
            sessionsDir?.listFiles().orEmpty().filter { it.extension == "hlbp" }.sortedBy { it.name }.forEach { put("sessions/${it.name}", it.readBytes()) }
            put("README.txt", SESSIONS_README.encodeToByteArray())
        }
        target
    }

    /**
     * Stores a cuff check and adds it to the calibration as a point (features from the reading's
     * pulse wave, when the watch sent it), then sends the updated calibration to the watch.
     */
    suspend fun addValidation(validation: BpValidationEntity) {
        dao.insertValidation(validation)
        val record = records.get(validation.readingId) ?: return
        val cal = calibration.first()?.takeIf { it.isValid(now()) } ?: return
        val at = record.entity.startedAtMs
        // With the reading's raw session, the cuff check teaches every channel (IR, BCG, ECG transit times).
        val sessionId = (record.summary as? RecordSummary.BloodPressure)?.sessionId
        val fromSession = sessionId?.let { session(it) }?.let { log ->
            withContext(Dispatchers.Default) { BpPipeline.capture(BpSessionReplay.input(log)) }
        }?.let { CalibrationPoint.of(it, validation.cuffSystolic, validation.cuffDiastolic, null, at) }
        val point = fromSession ?: run {
            val wave = records.wave(record) ?: return
            val features = withContext(Dispatchers.Default) { PpgFeatures.extract(wave, record.entity.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS) } ?: return
            CalibrationPoint(features, validation.cuffSystolic, validation.cuffDiastolic, null, null, at)
        }
        saveCalibration(upgradeIfNeeded(cal).withExtraPoint(point))
    }

    /** Hook for every record the watch delivers. */
    suspend fun onRecordSaved(meta: RecordMeta, wave: FloatArray?) {
        val summary = meta.summary as? RecordSummary.BloodPressure ?: return
        val refined = if (wave != null) refine(meta, wave, summary) else null
        if (refined != null) records.updateSummary(meta.id, refined)
        val final = refined ?: summary
        if (final.confirmed && final.safety != BpSafety.NONE) onSafety(final)
    }

    /** The personal model's refinement of a reading, or null when the model isn't (yet) better than the watch's estimate. */
    internal suspend fun refine(meta: RecordMeta, wave: FloatArray, summary: RecordSummary.BloodPressure): RecordSummary.BloodPressure? =
        withContext(Dispatchers.Default) {
            // The personal model learned steady readings; one taken in another state is left as the watch gave it.
            if (summary.rangeOnly || (summary.bodyState != null && summary.bodyState != "STEADY")) return@withContext null
            val fs = meta.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS
            val features = PpgFeatures.extract(wave, fs) ?: return@withContext null
            val samples = trainingSamples(excluding = meta.id)
            if (samples.size < HybridBpModel.MIN_SAMPLES) return@withContext null
            val model = embedders().mapNotNull { HybridBpModel(it).train(samples) }.minByOrNull { it.looMaeHybrid } ?: return@withContext null
            val classical = BpEstimate(summary.systolic, summary.diastolic, summary.pulse ?: 0, summary.uncertainty ?: 0)
            val (sys, dia) = model.correct(classical, features, wave) ?: return@withContext null
            Log.i(TAG, "refined ${summary.systolic}/${summary.diastolic} → $sys/$dia (LOO ${"%.1f".format(model.looMaeHybrid)} vs ${"%.1f".format(model.looMaeClassical)})")
            summary.copy(systolic = sys, diastolic = dia, algorithm = ALGORITHM_HYBRID, watchSystolic = summary.systolic, watchDiastolic = summary.diastolic)
        }

    /** Every cuff check whose reading's pulse wave is on the phone. */
    internal suspend fun trainingSamples(excluding: String? = null): List<HybridBpModel.Sample> =
        dao.validations().first().filter { it.readingId != excluding }.mapNotNull { v ->
            val record = records.get(v.readingId) ?: return@mapNotNull null
            val s = record.summary as? RecordSummary.BloodPressure ?: return@mapNotNull null
            val wave = records.wave(record) ?: return@mapNotNull null
            val features = PpgFeatures.extract(wave, record.entity.sampleRateHz.takeIf { it > 0 } ?: BpCalibration.PPG_FS) ?: return@mapNotNull null
            HybridBpModel.Sample(features, wave, s.watchSystolic ?: s.systolic, s.watchDiastolic ?: s.diastolic, v.cuffSystolic, v.cuffDiastolic)
        }

    /** The user's BP data for offline analysis (tools/bp-ml): calibration and cuff-checked readings with their raw PPG. */
    suspend fun dataset(): BpDataset? {
        val cal = calibration.first() ?: return null
        val entries = dao.validations().first().mapNotNull { v ->
            val record = records.get(v.readingId) ?: return@mapNotNull null
            val wave = records.wave(record) ?: return@mapNotNull null
            val s = record.summary as? RecordSummary.BloodPressure
            BpDatasetEntry(record.entity.startedAtMs, wave.toList(), v.cuffSystolic, v.cuffDiastolic, s?.systolic, s?.diastolic, s?.sessionId)
        }
        return BpDataset(sampleRateHz = BpCalibration.PPG_FS, calibration = cal, entries = entries)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        dao.deleteValidations()
        sync().sendCalibration(null)
    }

    private companion object {
        const val SESSIONS_README = """Heartline blood-pressure export
dataset.json: the calibration (every round with its channels) and every cuff check (cuff reading, the
watch's reading and its session id).
sessions/<id>.hlbp: one raw session each (gzip; format in shared/.../bp/BpSessionLog.kt): every sample
of every sensor with its timestamp, events, intermediate values and the result.
Read with tools/bp-ml/read_session.py.
"""
        const val TAG = "Heartline/BP"
        const val ALGORITHM_HYBRID = 4
    }
}
