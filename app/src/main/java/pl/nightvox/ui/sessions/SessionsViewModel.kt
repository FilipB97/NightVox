package pl.nightvox.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.nightvox.AppContainer
import pl.nightvox.data.SessionExporter
import pl.nightvox.data.db.ClipEntity
import pl.nightvox.data.db.SessionEntity
import pl.nightvox.data.db.SessionWithStats
import java.io.File

class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.clipRepository
    private val exporter = SessionExporter(container.exportsDir, repository)

    val sessions: StateFlow<List<SessionWithStats>> = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selected: StateFlow<SessionDetail?> = selectedId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(repository.observeSession(id), repository.clipsOfSession(id)) { session, clips ->
                    session?.let { SessionDetail(it, clips, describeSettings(it)) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(sessionId: String) {
        selectedId.value = sessionId
    }

    fun export(detail: SessionDetail) {
        viewModelScope.launch {
            runCatching { exporter.export(detail.session, detail.clips) }
                .onSuccess { _exported.value = it }
                .onFailure { _message.value = "Eksport nie powiódł się: ${it.message}" }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            _message.value = "Sesja usunięta"
        }
    }

    fun consumeExport() {
        _exported.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Parametry, przy jakich powstała ta noc — bez nich porównywanie sesji nie ma sensu. */
    private fun describeSettings(session: SessionEntity): List<Pair<String, String>> {
        val settings = repository.decodeSettings(session.settingsSnapshot) ?: return emptyList()
        return listOf(
            "triggerDeltaDb" to "${settings.triggerDeltaDb} dB",
            "attackFrames" to "${settings.attackFrames}",
            "preRollMs" to "${settings.preRollMs} ms",
            "hangoverMs" to "${settings.hangoverMs} ms",
            "mergeGapMs" to "${settings.mergeGapMs} ms",
            "minVoicedMs" to "${settings.minVoicedMs} ms",
            "maxClipMs" to "${settings.maxClipMs} ms",
        )
    }
}

data class SessionDetail(
    val session: SessionEntity,
    val clips: List<ClipEntity>,
    val settings: List<Pair<String, String>>,
)
