package pl.nightvox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.nightvox.NightVoxApp
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.service.RecorderState
import pl.nightvox.service.RecorderStateHolder
import pl.nightvox.util.EnvironmentStatus
import pl.nightvox.util.SystemChecks

class HomeViewModel(private val app: NightVoxApp) : ViewModel() {

    private val container = app.container

    val recorderState: StateFlow<RecorderState> = RecorderStateHolder.state

    val settings: StateFlow<NightVoxSettings> = container.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NightVoxSettings.DEFAULTS)

    private val _environment = MutableStateFlow(
        EnvironmentStatus(ignoringBatteryOptimizations = true, isCharging = true, freeBytes = -1),
    )
    val environment: StateFlow<EnvironmentStatus> = _environment.asStateFlow()

    /** Historia poziomu do live metera. Trzymana tutaj, nie w kompozycji. */
    private val _levelHistory = MutableStateFlow<List<Float>>(emptyList())
    val levelHistory: StateFlow<List<Float>> = _levelHistory.asStateFlow()

    init {
        viewModelScope.launch {
            // Tylko realne pomiary poziomu przesuwają wykres. Bez distinctUntilChangedBy
            // dopisywalibyśmy słupek także przy zamknięciu klipu czy odświeżeniu wolnego
            // miejsca, przez co meter „skakał” bez związku z dźwiękiem.
            RecorderStateHolder.state
                .distinctUntilChangedBy { it.levelUpdates to it.isRunning }
                .collect { state ->
                    if (!state.isRunning) {
                        if (_levelHistory.value.isNotEmpty()) _levelHistory.value = emptyList()
                        return@collect
                    }
                    val updated = _levelHistory.value + state.levelDb
                    _levelHistory.value = if (updated.size > HISTORY_CAPACITY) {
                        updated.subList(updated.size - HISTORY_CAPACITY, updated.size)
                    } else {
                        updated
                    }
                }
        }
        viewModelScope.launch {
            while (true) {
                refreshEnvironment()
                delay(ENVIRONMENT_REFRESH_MS)
            }
        }
    }

    fun refreshEnvironment() {
        _environment.value = SystemChecks.environment(app, container.clipsDir)
    }

    companion object {
        const val HISTORY_CAPACITY = 120
        private const val ENVIRONMENT_REFRESH_MS = 15_000L
    }
}
