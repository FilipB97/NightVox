package pl.nightvox.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.nightvox.AppContainer
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.work.RetentionWorker

data class StorageInfo(
    val clipBytes: Long = 0,
    val clipCount: Int = 0,
    val diagnosticsBytes: Long = 0,
    val debugDumpBytes: Long = 0,
    val orphanedFiles: Int = 0,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<NightVoxSettings> = container.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NightVoxSettings.DEFAULTS)

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshStorage()
    }

    fun update(transform: (NightVoxSettings) -> NightVoxSettings) {
        viewModelScope.launch { container.settingsStore.update(transform) }
    }

    fun setRetentionDays(context: Context, days: Int) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(retentionDays = days) }
            RetentionWorker.schedule(context)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            container.settingsStore.resetToDefaults()
            _message.value = "Przywrócono wartości domyślne"
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val clips = container.clipsDir.walkTopDown().filter { it.isFile }.toList()
            _storage.value = StorageInfo(
                clipBytes = clips.sumOf { it.length() },
                clipCount = clips.count { it.extension == "m4a" },
                diagnosticsBytes = container.diagnostics.totalBytes(),
                debugDumpBytes = container.debugDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                orphanedFiles = container.clipRepository.orphanedFiles().size,
            )
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch {
            container.diagnostics.clear()
            _message.value = "Log diagnostyczny wyczyszczony"
            refreshStorage()
        }
    }

    fun deleteDebugDumps() {
        viewModelScope.launch {
            container.debugDir.listFiles()?.forEach { it.delete() }
            _message.value = "Dumpy WAV skasowane"
            refreshStorage()
        }
    }

    fun deleteOrphanedFiles() {
        viewModelScope.launch {
            val orphans = container.clipRepository.orphanedFiles()
            orphans.forEach { it.delete() }
            _message.value = if (orphans.isEmpty()) {
                "Brak osieroconych plików"
            } else {
                "Usunięto ${orphans.size} osieroconych plików"
            }
            refreshStorage()
        }
    }

    fun runRetentionNow() {
        viewModelScope.launch {
            val settings = container.settingsStore.current()
            val deleted = container.clipRepository.applyRetention(
                retentionDays = settings.retentionDays,
                discardedRetentionDays = settings.discardedRetentionDays,
            )
            _message.value = if (deleted == 0) "Nic nie kwalifikowało się do skasowania" else "Skasowano $deleted klipów"
            refreshStorage()
        }
    }

    fun diagnosticsFile(): File? {
        // Wpisy lecą przez kolejkę na osobny wątek — bez flusha udostępnilibyśmy log
        // bez ostatnich, czyli zwykle najciekawszych, linii.
        container.diagnostics.flush()
        return container.diagnostics.snapshotForSharing()
    }

    fun consumeMessage() {
        _message.value = null
    }
}
