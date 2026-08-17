package pl.nightvox.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.nightvox.NightVoxApp
import pl.nightvox.audio.AudioCapture
import pl.nightvox.audio.Frame
import pl.nightvox.audio.GateConfig
import pl.nightvox.audio.LevelMeter
import kotlin.math.ceil

enum class CalibrationPhase {
    IDLE,

    /** 15 s ciszy w sypialni — mierzymy realne tło, nie zgadujemy. */
    MEASURING_FLOOR,

    /** Tło zmierzone, proponujemy próg. */
    FLOOR_READY,

    /** 5 s „powiedz coś cicho, jak przez sen” — weryfikacja, czy próg by zadziałał. */
    VERIFYING,

    VERIFIED,
}

data class CalibrationState(
    val phase: CalibrationPhase = CalibrationPhase.IDLE,
    val remainingMs: Long = 0,
    val liveLevelDb: Float = LevelMeter.MIN_DBFS,
    val measuredFloorDb: Float = 0f,
    val quietPercentileDb: Float = 0f,
    val suggestedDeltaDb: Float = 0f,
    val speechPeakDb: Float = LevelMeter.MIN_DBFS,
    val speechMeanDb: Float = LevelMeter.MIN_DBFS,
    val wouldTrigger: Boolean = false,
    val longestRunFrames: Int = 0,
    val error: String? = null,
) {
    val thresholdDb: Float get() = measuredFloorDb + suggestedDeltaDb
}

/**
 * Kalibracja z §8 planu — jedyny sposób, żeby nie zgadywać progu.
 *
 * Etap 1: 15 s ciszy w sypialni daje realne tło i rozrzut. Etap 2: 5 s cichej wypowiedzi
 * sprawdza, czy przy zaproponowanym progu bramka **naprawdę** by się wyzwoliła. Bez etapu 2
 * kalibracja jest tylko ładnie wyglądającą liczbą.
 */
class CalibrationViewModel(private val app: NightVoxApp) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var capture: AudioCapture? = null
    private var job: Job? = null

    fun startFloorMeasurement() {
        run(CalibrationPhase.MEASURING_FLOOR, FLOOR_DURATION_MS) { levels ->
            val floor = median(levels)
            val quietP95 = percentile(levels, 0.95f)
            // Próg musi siedzieć nad rozrzutem ciszy plus margines, inaczej co druga ramka
            // tła wyzwoli nagrywanie.
            val suggested = ceil((quietP95 - floor) + HEADROOM_DB)
                .coerceIn(SUGGESTED_MIN_DB, SUGGESTED_MAX_DB)
            _state.value = _state.value.copy(
                phase = CalibrationPhase.FLOOR_READY,
                remainingMs = 0,
                measuredFloorDb = floor,
                quietPercentileDb = quietP95,
                suggestedDeltaDb = suggested,
            )
        }
    }

    fun startVerification() {
        val current = _state.value
        if (current.phase != CalibrationPhase.FLOOR_READY && current.phase != CalibrationPhase.VERIFIED) return
        run(CalibrationPhase.VERIFYING, SPEECH_DURATION_MS) { levels ->
            val threshold = current.measuredFloorDb + current.suggestedDeltaDb
            var run = 0
            var longest = 0
            for (level in levels) {
                if (level > threshold) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
            _state.value = _state.value.copy(
                phase = CalibrationPhase.VERIFIED,
                remainingMs = 0,
                speechPeakDb = levels.maxOrNull() ?: LevelMeter.MIN_DBFS,
                speechMeanDb = if (levels.isEmpty()) LevelMeter.MIN_DBFS else levels.average().toFloat(),
                longestRunFrames = longest,
                wouldTrigger = longest >= DEFAULT_ATTACK_FRAMES,
            )
        }
    }

    fun adjustSuggestion(deltaDb: Float) {
        _state.value = _state.value.copy(
            suggestedDeltaDb = deltaDb.coerceIn(SUGGESTED_MIN_DB, SUGGESTED_MAX_DB),
        )
    }

    fun applySuggestion(onApplied: () -> Unit) {
        val delta = _state.value.suggestedDeltaDb
        viewModelScope.launch {
            app.container.settingsStore.update { it.copy(triggerDeltaDb = delta) }
            app.container.diagnostics.log(
                "calibration",
                "zastosowano triggerDeltaDb=$delta przy tle ${_state.value.measuredFloorDb}",
            )
            onApplied()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        stopCapture()
        _state.value = _state.value.copy(phase = CalibrationPhase.IDLE, remainingMs = 0)
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    private fun run(phase: CalibrationPhase, durationMs: Long, onDone: (List<Float>) -> Unit) {
        job?.cancel()
        stopCapture()
        _state.value = _state.value.copy(phase = phase, remainingMs = durationMs, error = null)

        val frames = Channel<Frame>(capacity = 256)
        val audioCapture = AudioCapture(
            context = app,
            listener = object : AudioCapture.Listener {
                override fun onFrame(frame: Frame) {
                    frames.trySend(frame)
                }

                override fun onCaptureError(reason: String, attempt: Int, delayMs: Long) = Unit
                override fun onCaptureRestarted(source: Int) = Unit
                override fun onSilencedChanged(silenced: Boolean) {
                    if (silenced) {
                        _state.value = _state.value.copy(error = "System wyciszył mikrofon — przerwij i spróbuj ponownie")
                    }
                }

                override fun onFatalError(reason: String) {
                    _state.value = _state.value.copy(phase = CalibrationPhase.IDLE, error = reason)
                    frames.close()
                }
            },
        )
        capture = audioCapture
        audioCapture.start()

        job = viewModelScope.launch {
            val levels = ArrayList<Float>(512)
            val startedAt = System.currentTimeMillis()
            val frameMs = GateConfig().frameMs

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= durationMs) break
                val frame = frames.receiveCatching().getOrNull() ?: break
                val db = LevelMeter.rmsDbfs(frame.samples)
                levels.add(db)
                _state.value = _state.value.copy(
                    liveLevelDb = db,
                    remainingMs = (durationMs - elapsed).coerceAtLeast(0),
                )
            }
            stopCapture()
            frames.close()
            // Pierwsze ramki po otwarciu mikrofonu bywają śmieciowe (rozruch HAL).
            val settled = levels.drop((WARMUP_SKIP_MS / frameMs).toInt().coerceAtMost(levels.size))
            if (settled.isEmpty()) {
                _state.value = _state.value.copy(
                    phase = CalibrationPhase.IDLE,
                    error = "Nie udało się nic zmierzyć — sprawdź uprawnienie do mikrofonu",
                )
            } else {
                onDone(settled)
            }
        }
    }

    private fun stopCapture() {
        capture?.stop()
        capture = null
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return LevelMeter.MIN_DBFS
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return LevelMeter.MIN_DBFS
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        const val FLOOR_DURATION_MS = 15_000L
        const val SPEECH_DURATION_MS = 5_000L
        private const val WARMUP_SKIP_MS = 500L
        private const val HEADROOM_DB = 6f
        private const val SUGGESTED_MIN_DB = 6f
        private const val SUGGESTED_MAX_DB = 24f
        private const val DEFAULT_ATTACK_FRAMES = 3
    }
}
