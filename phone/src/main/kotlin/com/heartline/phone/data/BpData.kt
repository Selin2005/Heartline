package com.heartline.phone.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.model.RecordKind
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

@Entity(tableName = "bp_calibrations")
data class BpCalibrationEntity(@PrimaryKey val id: String, val createdAtMs: Long, val json: String)

@Dao
interface BpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(calibration: BpCalibrationEntity)

    @Query("SELECT * FROM bp_calibrations ORDER BY createdAtMs DESC LIMIT 1")
    fun latest(): Flow<BpCalibrationEntity?>

    @Query("DELETE FROM bp_calibrations")
    suspend fun deleteAll()
}

/** Blood-pressure calibration (source of truth on the phone) and readings from the watch. */
class BpRepository(
    private val dao: BpDao,
    private val records: RecordRepository,
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

    /** Re-sends the active calibration (after the watch says hello). */
    suspend fun resendCalibration() {
        calibration.first()?.let { sync().sendCalibration(it) }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        sync().sendCalibration(null)
    }
}
