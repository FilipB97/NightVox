package pl.nightvox.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.nightvox.audio.GateState
import pl.nightvox.audio.LevelMeter

/**
 * Migawka stanu nagrywania konsumowana przez UI.
 *
 * Trzymana w obiekcie procesu, nie w bindingu do serwisu: UI ma pokazywać poziom także
 * wtedy, gdy Activity dopiero wraca na wierzch, a bindowanie do FGS tylko po to, żeby
 * odczytać `StateFlow`, to niepotrzebna warstwa.
 */
data class RecorderState(
    val isRunning: Boolean = false,
    val sessionId: String? = null,
    val startedAtMs: Long = 0L,
    val gateState: GateState = GateState.WARMUP,
    val levelDb: Float = LevelMeter.MIN_DBFS,
    val floorDb: Float = LevelMeter.MIN_DBFS,
    val thresholdDb: Float = LevelMeter.MIN_DBFS,
    /**
     * Rośnie z każdym pomiarem poziomu. Live meter dopisuje słupek tylko wtedy, gdy ten
     * licznik drgnie — inaczej wykres przewijałby się także przy zdarzeniach niezwiązanych
     * z dźwiękiem (zamknięty klip, zmiana wolnego miejsca).
     */
    val levelUpdates: Long = 0,
    val clipCount: Int = 0,
    val discardedCount: Int = 0,
    val interruptions: Int = 0,
    val isSilenced: Boolean = false,
    val warmupRemainingMs: Long = 0L,
    val audioSource: String = "",
    val lastError: String? = null,
    val freeBytes: Long = -1L,
) {
    val isRecordingClip: Boolean
        get() = gateState == GateState.RECORDING || gateState == GateState.HANGOVER

    val isWarmingUp: Boolean get() = gateState == GateState.WARMUP
}

object RecorderStateHolder {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)

    fun reset() {
        _state.value = RecorderState()
    }
}
