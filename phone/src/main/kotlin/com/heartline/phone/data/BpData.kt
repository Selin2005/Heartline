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

    /** Re-sends the active calibration (after the watch says hello); [orNull] also tells the watch to drop its copy when there is none. */
    suspend fun resendCalibration(orNull: Boolean = false) {
        val current = calibration.first()
        if (current != null || orNull) sync().sendCalibration(current)
    }

    val validations: Flow<List<BpValidationEntity>> = dao.validations()

    suspend fun addValidation(validation: BpValidationEntity) = dao.insertValidation(validation)

    suspend fun deleteAll() {
        dao.deleteAll()
        dao.deleteValidations()
        sync().sendCalibration(null)
    }
}
