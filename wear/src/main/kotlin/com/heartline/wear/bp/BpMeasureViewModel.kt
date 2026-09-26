// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.bp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpConfirmation
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpSessionHeader
import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.BpWindowSelector
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.PreciseInput
import com.heartline.shared.bp.PulseRate
import com.heartline.shared.dsp.StreamingPpgFilter
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.Protocol
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.BpAuxSensors
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.ImuRecorder
import com.heartline.wear.sensor.MotionMeter
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Quick: PPG + motion, 20–60 s, no touch. Precise: finger on the key (ECG), with the arm-raise maneuver. */
enum class BpMode { QUICK, PRECISE }

/** What the user is asked to do during a precise recording (the arm-raise maneuver), by elapsed seconds. */
enum class BpPhase(val untilSeconds: Int) {
    REST(12),
    RAISE(18),
    HOLD_UP(20),
    LOWER(26),
    REST_AGAIN(34),
    ;

    companion object {
        const val TOTAL_SECONDS = 34

        fun at(seconds: Double): BpPhase = entries.firstOrNull { seconds < it.untilSeconds } ?: REST_AGAIN
    }
}

sealed interface BpState {
    data object Idle : BpState

    data object NeedsCalibration : BpState

    /** Reading skin temperature / conductance before the main recording. */
    data object Preparing : BpState

    /**
     * [trace]: filtered, upright PPG for the sweep; [bpm]: live pulse. [settling]: the minimum
     * time is over and the recording continues until the pulse is steady. [phase]: the maneuver
     * step in precise mode; [contact]: in precise mode, the finger on the key.
     */
    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val contact: Boolean,
        val bpm: Int? = null,
        val endIndex: Long = trace.size.toLong(),
        val settling: Boolean = false,
        val phase: BpPhase? = null,
    ) : BpState

    /**
     * [beyondCalibration]: an extrapolation (shown, with its wider ±). [confirmed]: a second
     * reading within 10 minutes agreed. [safety]: very high or low, check with a cuff.
     * Algorithm 6: always one number with its ±; [bodyState] and [channels] say what it rests
     * on; [notValidated]: a condition (pregnancy) cuffless readings aren't validated for;
     * [ectopicBeats]: premature beats that were left out.
     */
    data class Done(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val category: BpCategory,
        val uncertainty: Int = 0,
        val beyondCalibration: Boolean = false,
        val confirmed: Boolean = false,
        val safety: BpSafety = BpSafety.NONE,
        val uncertaintyDia: Int = 0,
        val notValidated: Boolean = false,
        val ectopicBeats: Int = 0,
        val bodyState: HemodynamicState = HemodynamicState.STEADY,
        val channels: List<BpChannel> = emptyList(),
    ) : BpState {
        val needsConfirming get() = (beyondCalibration || safety != BpSafety.NONE) && !confirmed
    }

    /** The recording isn't a trustworthy pulse wave (never used for a real change in pressure). */
    data object OutOfRange : BpState

    /** The arm moved during the recording. */
    data object Moving : BpState

    data class CalibrationRecorded(val round: Int) : BpState

    data object PoorSignal : BpState

    data class Failed(val problem: SensorProblem) : BpState
}

/**
 * One blood-pressure session (algorithm 6, docs/algorithms/BP_ALGORITHM.md).
 *
 * - Quick mode: skin temperature and conductance first (when the watch has them), then green,
 *   infrared and red PPG with the accelerometer, gyroscope and rotation vector, for at least
 *   [minSeconds] and until the pulse is steady (at most [maxSeconds]; 45 s for an irregular rhythm).
 * - Precise mode: ECG with its PPG and the motion sensors for [BpPhase.TOTAL_SECONDS], with the
 *   arm-raise maneuver in the middle.
 *
 * Every sample of every sensor, every intermediate value and the result are written to a raw
 * session log ([BpSessionLog]) that goes to the phone, for developing the algorithm on real data.
 * In measure mode the session becomes a reading; in calibration mode its channels are sent to the
 * phone, where they are paired with the user's cuff reading.
 */
class BpMeasureViewModel(
    private val source: PpgSource,
    private val bpStore: WatchBpStore,
    private val records: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val minSeconds: Int = BpWindowSelector.WINDOW_SECONDS,
    private val uiIntervalMs: Long = 40,
    private val imu: ImuRecorder = ImuRecorder.NONE,
    private val ecg: EcgSource? = null,
    private val aux: BpAuxSensors = BpAuxSensors.NONE,
    private val heightCm: () -> Double? = { null },
    private val device: String = "",
    private val appVersion: String = "",
    private val maxSeconds: Int = BpWindowSelector.MAX_SECONDS,
) : ViewModel() {
    private val mutable = MutableStateFlow<BpState>(BpState.Idle)
    val state: StateFlow<BpState> = mutable.asStateFlow()
    private var job: Job? = null

    /** Calibration capture requested by the phone, if any. */
    val pendingCapture: StateFlow<CaptureRequest?> get() = bpStore.pendingCapture

    /** Precise mode needs the ECG sensor. */
    val preciseAvailable: Boolean get() = ecg != null

    fun checkReady() {
        if (bpStore.pendingCapture.value == null && bpStore.calibration.value?.isValid(now()) != true) mutable.value = BpState.NeedsCalibration
    }

    fun start(mode: BpMode = BpMode.QUICK) {
        // Only a session still recording blocks a new one: a finished job may be "active" for a
        // moment after publishing its result, and "Measure again" must not be lost to that.
        val recording = mutable.value is BpState.Preparing || mutable.value is BpState.Measuring
        if (job?.isActive == true && recording) return
        val capture = bpStore.pendingCapture.value
        if (capture == null && bpStore.calibration.value?.isValid(now()) != true) {
            mutable.value = BpState.NeedsCalibration
            return
        }
        val chosen = if (capture?.precise == true || mode == BpMode.PRECISE) BpMode.PRECISE else BpMode.QUICK
        val effective = if (chosen == BpMode.PRECISE && ecg == null) BpMode.QUICK else chosen
        val startedAt = now()
        val id = UUID.randomUUID().toString()
        val log = BpSessionRecorder(id, if (capture != null) "calibration" else "measure", startedAt, now)
        job = viewModelScope.launch {
            mutable.value = BpState.Preparing
            log.event("aux.start")
            val temp = aux.skinTemp()
            temp?.let {
                log.stream(BpSessionStreams.SKIN_TEMP, *BpSessionStreams.SKIN_TEMP_COLUMNS)
                    .append(now() * 1_000_000L, it.objectC.toFloat(), (it.ambientC ?: Double.NaN).toFloat(), 0f)
            }
            val eda = aux.skinConductance(EDA_SECONDS)
            eda?.let { log.stream(BpSessionStreams.EDA, *BpSessionStreams.EDA_COLUMNS).append(now() * 1_000_000L, it.toFloat()) }
            log.capability("skinTemp", (temp != null).toString())
            log.capability("eda", (eda != null).toString())
            log.value(BpSessionStreams.VALUE_HEIGHT, heightCm())
            imu.start()
            log.event("recording.start", effective.name)
            val recorded = if (effective == BpMode.PRECISE) recordPrecise(log) else recordQuick(log, capture)
            val movement = imu.stop()
            val streams = imu.streams()
            streams.all().forEach { log.attach(it) }
            log.event("recording.end")
            log.value("motion.sdAll", movement)
            val header: BpSessionHeader.() -> BpSessionHeader = {
                copy(
                    mode = if (effective == BpMode.PRECISE) BpSessionHeader.MODE_PRECISE else BpSessionHeader.MODE_QUICK,
                    device = device,
                    appVersion = appVersion,
                    algorithm = ALGORITHM,
                    calibrationRound = capture?.round
                )
            }
            val input = when (recorded) {
                is Recorded.Failure -> {
                    finish(log, header)
                    mutable.value = recorded.state
                    return@launch
                }
                is Recorded.Session -> recorded.input.copy(imu = streams, skinTempC = temp?.objectC, edaMicroSiemens = eda, heightCm = heightCm())
            }
            // Quick mode must be still; the precise maneuver moves the arm on purpose.
            if (effective == BpMode.QUICK) {
                val t = input.greenTimesNs
                val still = if (t != null && t.size > minSeconds * input.fs) streams.motionSd(t[t.size - minSeconds * input.fs], t.last()) else movement
                log.value("motion.sdWindow", still)
                if (still != null && still > MotionMeter.MAX_STILL) {
                    Log.i(TAG, "BP: moved during recording (${"%.2f".format(still)} m/s²)")
                    log.note("result", "moving")
                    finish(log, header)
                    mutable.value = BpState.Moving
                    return@launch
                }
            }
            mutable.value = if (capture != null) {
                finishCalibration(capture, input, log, id).also { finish(log, header) }
            } else {
                finishMeasurement(input, startedAt, log, id, header)
            }
        }
    }

    private sealed interface Recorded {
        data class Session(val input: BpSessionInput) : Recorded

        data class Failure(val state: BpState) : Recorded
    }

    /** Green / IR / red PPG until the pulse is steady (at least [minSeconds], at most [maxSeconds]). */
    private suspend fun recordQuick(log: BpSessionRecorder, capture: CaptureRequest?): Recorded {
        val fs = source.sampleRateHz
        val profile = bpStore.calibration.value?.profile
        val minimum = if (capture == null && profile?.atrialFibrillation == true) maxOf(minSeconds, profile.recordingSeconds) else minSeconds
        val maximum = maxOf(maxSeconds, minimum)
        val green = FloatList()
        val ir = FloatList()
        val times = LongList()
        val ppgLog = log.stream(BpSessionStreams.PPG, *BpSessionStreams.PPG_COLUMNS)
        val live = LivePpg(fs)
        var lastUi = 0L
        var lastCheck = 0
        var stop = false
        var failure: SensorProblem? = null
        val startNs = now() * 1_000_000L
        mutable.value = BpState.Measuring(0f, minimum, FloatArray(0), contact = true)
        source.stream()
            .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
            .takeWhile { !stop }
            .collect { chunk ->
                live.add(chunk.samples)
                // Every point exactly as the SDK gave it goes to the log, contact or not.
                if (chunk.points.isNotEmpty()) {
                    chunk.points.forEach { p ->
                        ppgLog.append(p.timestampMs * 1_000_000L, p.green, p.ir, p.red, p.greenStatus.toFloat(), p.irStatus.toFloat(), p.redStatus.toFloat())
                    }
                } else {
                    chunk.samples.forEachIndexed { i, v ->
                        ppgLog.append(startNs + (ppgLog.size + i).toLong() * 1_000_000_000L / fs, v, Float.NaN, Float.NaN, if (chunk.contact) 0f else 1f, -1f, -1f)
                    }
                }
                if (chunk.contact) {
                    val base = times.size
                    chunk.samples.forEachIndexed { i, v ->
                        green.add(v)
                        ir.add(chunk.ir?.getOrNull(i) ?: Float.NaN)
                        times.add(chunk.points.getOrNull(i)?.timestampMs?.times(1_000_000L) ?: (startNs + (base + i).toLong() * 1_000_000_000L / fs))
                    }
                }
                val seconds = green.size / fs
                if (seconds >= minimum && seconds >= lastCheck + CHECK_EVERY_SECONDS) {
                    lastCheck = seconds
                    val snapshot = green.toArray()
                    stop = seconds >= maximum || withContext(Dispatchers.Default) { BpWindowSelector.shouldStop(snapshot, fs, minimum) }
                    if (stop) log.event("stop", "after ${seconds}s")
                }
                val t = now()
                if (t - lastUi >= uiIntervalMs) {
                    lastUi = t
                    val settling = seconds >= minimum
                    mutable.value = BpState.Measuring(
                        if (settling) 1f else green.size.toFloat() / (minimum * fs),
                        if (settling) 0 else (minimum * fs - green.size + fs - 1) / fs,
                        live.recent(3.0),
                        chunk.contact,
                        live.bpm(t),
                        live.total,
                        settling = settling,
                    )
                }
            }
        log.capability("ppg.ir", (ir.any { it.isFinite() }).toString())
        failure?.let {
            log.note("result", "failed $it")
            return Recorded.Failure(BpState.Failed(it))
        }
        val irArray = ir.toArray().takeIf { a -> a.count { it.isFinite() } > a.size * 0.9 }
        return Recorded.Session(BpSessionInput(green.toArray(), fs, times.toArray(), irArray))
    }

    /** ECG with its PPG (500 Hz) for [BpPhase.TOTAL_SECONDS] of finger contact, through the maneuver. */
    private suspend fun recordPrecise(log: BpSessionRecorder): Recorded {
        val source = ecg ?: return Recorded.Failure(BpState.Failed(SensorProblem.NOT_SUPPORTED))
        val fs = source.sampleRateHz
        val target = BpPhase.TOTAL_SECONDS * fs
        val ecgValues = FloatList()
        val ppgValues = FloatList()
        val ecgLog = log.stream(BpSessionStreams.ECG, *BpSessionStreams.ECG_COLUMNS)
        val live = LivePpg(fs)
        var firstNs: Long? = null
        var missingPpg = false
        var lastUi = 0L
        var phase: BpPhase? = null
        var failure: SensorProblem? = null
        mutable.value = BpState.Measuring(0f, BpPhase.TOTAL_SECONDS, FloatArray(0), contact = false, phase = BpPhase.REST)
        source.stream()
            .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
            .takeWhile { ecgValues.size < target }
            .collect { chunk ->
                val n = chunk.samples.size
                val lastNs = (chunk.timestampMs ?: now()) * 1_000_000L
                for (i in 0 until n) {
                    val t = lastNs - (n - 1 - i).toLong() * 1_000_000_000L / fs
                    ecgLog.append(t, chunk.samples[i], chunk.ppg?.getOrNull(i) ?: Float.NaN, if (chunk.leadOff) 1f else 0f)
                }
                if (chunk.leadOff || chunk.saturated) {
                    // The recording must be one continuous stretch (its samples are timed from its
                    // start, to line up with the motion sensors): losing the finger starts it again.
                    if (ecgValues.size > 0) {
                        log.event("contact.lost", "after ${ecgValues.size} samples; restarting")
                        ecgValues.clear()
                        ppgValues.clear()
                        firstNs = null
                        phase = null
                    }
                    mutable.value = BpState.Measuring(0f, BpPhase.TOTAL_SECONDS, live.recent(3.0), contact = false, phase = BpPhase.REST)
                    return@collect
                }
                if (chunk.ppg == null) missingPpg = true
                if (firstNs == null) firstNs = lastNs - (n - 1).toLong() * 1_000_000_000L / fs
                val take = minOf(n, target - ecgValues.size)
                for (i in 0 until take) {
                    ecgValues.add(chunk.samples[i])
                    ppgValues.add(chunk.ppg?.getOrNull(i) ?: Float.NaN)
                }
                chunk.ppg?.let { live.add(it.copyOf(take)) }
                val seconds = ecgValues.size.toDouble() / fs
                val current = BpPhase.at(seconds)
                if (current != phase) {
                    phase = current
                    log.event("phase", current.name)
                }
                val t = now()
                if (t - lastUi >= uiIntervalMs) {
                    lastUi = t
                    mutable.value = BpState.Measuring(
                        ecgValues.size.toFloat() / target,
                        (target - ecgValues.size + fs - 1) / fs,
                        live.recent(3.0),
                        !chunk.leadOff,
                        live.bpm(t),
                        live.total,
                        phase = current,
                    )
                }
            }
        failure?.let {
            log.note("result", "failed $it")
            return Recorded.Failure(BpState.Failed(it))
        }
        log.capability("ecg.ppg", (!missingPpg).toString())
        val start = firstNs
        if (ecgValues.size < BpWindowSelector.WINDOW_SECONDS * fs / 2 || start == null || missingPpg) {
            log.note("result", "precise: ${ecgValues.size} samples, ppg ${!missingPpg}")
            return Recorded.Failure(BpState.PoorSignal)
        }
        val ppg = ppgValues.toArray()
        // The raised window from the arm's actual angle; the scheduled phase if the motion sensor didn't see it.
        val raised = imu.streams().raisedWindow() ?: (start + BpPhase.RAISE.untilSeconds * 1_000_000_000L - 3_000_000_000L)..(start + BpPhase.HOLD_UP.untilSeconds * 1_000_000_000L)
        log.note(BpSessionStreams.NOTE_RAISED_FROM, raised.first.toString())
        log.note(BpSessionStreams.NOTE_RAISED_TO, raised.last.toString())
        val times = LongArray(ppg.size) { start + it.toLong() * 1_000_000_000L / fs }
        return Recorded.Session(BpSessionInput(ppg, fs, times, precise = PreciseInput(ecgValues.toArray(), ppg, fs, start, raised)))
    }

    private suspend fun finishCalibration(capture: CaptureRequest, input: BpSessionInput, log: BpSessionRecorder, sessionId: String): BpState {
        val channels = withContext(Dispatchers.Default) { BpPipeline.capture(input) }
        log.features("green", channels?.features)
        log.features("ir", channels?.irFeatures)
        log.value("bcg.pttMs", channels?.bcgPttMs)
        log.value("precise.patMs", channels?.patMs)
        log.value("precise.pepMs", channels?.pepMs)
        log.value("precise.pttMs", channels?.pttMs)
        Log.i(TAG, "BP calibration round ${capture.round}: $channels")
        val features = channels?.features
        if (channels == null || features == null || features.quality < BpEstimator.MIN_QUALITY || features.beats < BpEstimator.MIN_BEATS) {
            log.note("result", "poor signal")
            return BpState.PoorSignal
        }
        val result = CaptureResult(
            UUID.randomUUID().toString(),
            capture.captureId,
            capture.round,
            features,
            channels.ppg.takeIf { channels.fs == BpCalibration.PPG_FS },
            channels.gravity,
            channels,
            sessionId,
        )
        records.enqueueMessage(result.id, Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        sync.schedule()
        bpStore.setPendingCapture(null)
        log.note("result", "calibration round ${capture.round}")
        return BpState.CalibrationRecorded(capture.round)
    }

    private suspend fun finishMeasurement(
        input: BpSessionInput,
        startedAt: Long,
        log: BpSessionRecorder,
        sessionId: String,
        header: BpSessionHeader.() -> BpSessionHeader,
    ): BpState {
        val result = withContext(Dispatchers.Default) { BpPipeline.run(bpStore.calibration.value, input, now(), bpStore.history, log) }
        val outcome = result.outcome
        Log.i(TAG, "BP measurement: samples=${input.green.size} outcome=$outcome")
        return when (outcome) {
            BpOutcome.NeedsCalibration -> BpState.NeedsCalibration.also { finish(log, header) }
            BpOutcome.PoorSignal -> BpState.PoorSignal.also { finish(log, header) }
            is BpOutcome.OutOfRange -> BpState.OutOfRange.also { finish(log, header) }
            is BpOutcome.Ok -> {
                val e = outcome.estimate
                val t = now()
                val previous = bpStore.lastReading
                val confirmed = previous != null && BpConfirmation.confirms(previous.toEstimate(), previous.atMs, e, t)
                bpStore.lastReading = LastBpReading.of(e, t)
                val features = result.features
                if (!e.beyondCalibration && e.state.steady && features != null) bpStore.addHistory(features)
                val window = result.window ?: input.green.indices
                val wave = input.green.copyOfRange(window.first, window.last + 1)
                val recordId = UUID.randomUUID().toString()
                val meta = RecordMeta(
                    recordId,
                    RecordKind.BLOOD_PRESSURE,
                    startedAt,
                    (t - startedAt).coerceAtLeast(0),
                    input.fs,
                    wave.size,
                    RecordSummary.BloodPressure(
                        e.systolic,
                        e.diastolic,
                        e.pulse,
                        e.uncertaintySys,
                        algorithm = ALGORITHM,
                        beyondCalibration = e.beyondCalibration,
                        confirmed = confirmed,
                        channels = e.channels.joinToString(",") { it.channel.name },
                        bodyState = e.state.state.name,
                        mode = if (input.precise != null) BpSessionHeader.MODE_PRECISE else BpSessionHeader.MODE_QUICK,
                        sessionId = sessionId,
                    ),
                )
                // The raw pulse wave goes to the phone too: it lets the phone's personal model refine
                // the reading and lets the algorithm be re-evaluated on real data later.
                records.add(meta, wave)
                finish(log) { header().copy(recordId = recordId) }
                BpState.Done(
                    e.systolic,
                    e.diastolic,
                    e.pulse,
                    BpCategory.of(e.systolic, e.diastolic),
                    e.uncertaintySys,
                    e.beyondCalibration,
                    confirmed,
                    e.safety,
                    uncertaintyDia = e.uncertaintyDia,
                    notValidated = e.notValidated,
                    ectopicBeats = e.ectopicBeats,
                    bodyState = e.state.state,
                    channels = e.channels.map { it.channel },
                )
            }
        }
    }

    /** Stores the raw session log for the phone (every session, including failed ones). */
    private suspend fun finish(log: BpSessionRecorder, header: BpSessionHeader.() -> BpSessionHeader) {
        val session = log.build(header)
        Log.i(RAW_TAG, "session ${session.header.id}: ${session.streams.joinToString { "${it.name}=${it.size}@${"%.1f".format(it.rateHz())}Hz" }} values=${session.header.values.size}")
        session.header.values.entries.sortedBy { it.key }.chunked(12).forEach { chunk -> Log.d(RAW_TAG, chunk.joinToString { "${it.key}=${"%.4g".format(it.value)}" }) }
        runCatching { records.addSession(session) }.onFailure { Log.w(RAW_TAG, "session not stored", it) }
        sync.schedule()
    }

    fun cancel() {
        if (job?.isActive == true) imu.stop()
        job?.cancel()
        mutable.value = BpState.Idle
    }

    fun reset() {
        mutable.value = BpState.Idle
    }

    /** Growable primitive lists (a 60 s recording at 100 Hz, or 34 s at 500 Hz). */
    private class FloatList {
        private var a = FloatArray(4096)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun any(p: (Float) -> Boolean) = (0 until size).any { p(a[it]) }

        fun clear() {
            size = 0
        }

        fun toArray() = a.copyOf(size)
    }

    private class LongList {
        private var a = LongArray(4096)
        var size = 0
            private set

        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun toArray() = a.copyOf(size)
    }

    /** Band-passed PPG, flipped upright when the raw signal is inverted, and a live pulse. */
    private class LivePpg(private val fs: Int) {
        private val filter = StreamingPpgFilter(fs)
        private val ring = FloatArray(fs * 8)
        var total = 0L
            private set
        private var inverted: Boolean? = null
        private var lastBpmAt = 0L
        private var bpm: Int? = null

        fun add(samples: FloatArray) {
            filter.process(samples).forEach {
                ring[(total % ring.size).toInt()] = it
                total++
            }
            // Decide the polarity once there's enough signal; raw watch PPG is usually upside down.
            if (inverted == null && total >= fs * 4) inverted = PpgFeatures.isInverted(raw(4.0))
        }

        private fun raw(seconds: Double): FloatArray {
            val n = (seconds * fs).toInt().coerceAtMost(minOf(total, ring.size.toLong()).toInt())
            return FloatArray(n) { k -> ring[((total - n + k) % ring.size).toInt()] }
        }

        fun recent(seconds: Double): FloatArray = raw(seconds).let { x -> if (inverted == true) FloatArray(x.size) { -x[it] } else x }

        fun bpm(nowMs: Long): Int? {
            if (nowMs - lastBpmAt >= 1_000) {
                lastBpmAt = nowMs
                bpm = PulseRate.bpm(recent(6.0), fs) ?: bpm
            }
            return bpm
        }
    }

    private companion object {
        const val TAG = "Heartline/BP"
        const val RAW_TAG = "Heartline/BpRaw"
        const val ALGORITHM = 6
        const val CHECK_EVERY_SECONDS = 2
        const val EDA_SECONDS = 5
    }
}
