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
import pl.nightvox.data.NightVoxSettings
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
                combine(repository.observeSession(id), repository.allClipsOfSession(id)) { session, all ->
                    session?.let { entity ->
                        val settings = repository.decodeSettings(entity.settingsSnapshot)
                        SessionDetail(
                            session = entity,
                            clips = all.filter { !it.isDiscarded },
                            discarded = all.filter { it.isDiscarded },
                            settings = describeSettings(settings),
                            speechThreshold = settings
                                ?.takeIf { it.speechFilterEnabled }
                                ?.speechFilterThreshold,
                        )
                    }
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
            runCatching { exporter.export(detail.session, detail.clips, detail.discarded) }
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
    private fun describeSettings(settings: NightVoxSettings?): List<Pair<String, String>> {
        if (settings == null) return emptyList()
        return listOf(
            "triggerDeltaDb" to "${settings.triggerDeltaDb} dB",
            "minTriggerDb" to "${settings.minTriggerDb} dBFS",
            "attackFrames" to "${settings.attackFrames}",
            "preRollMs" to "${settings.preRollMs} ms",
            "hangoverMs" to "${settings.hangoverMs} ms",
            "mergeGapMs" to "${settings.mergeGapMs} ms",
            "minVoicedMs" to "${settings.minVoicedMs} ms",
            "maxClipMs" to "${settings.maxClipMs} ms",
            "filtr mowy" to if (settings.speechFilterEnabled) {
                String.format(java.util.Locale.US, "próg %.2f", settings.speechFilterThreshold)
            } else {
                "wyłączony"
            },
        )
    }
}

data class SessionDetail(
    val session: SessionEntity,
    /** To, co przeszło przez bramkę i filtr mowy. */
    val clips: List<ClipEntity>,
    /** To, co bramka albo filtr mowy odrzuciły — kosz tej konkretnej nocy. */
    val discarded: List<ClipEntity>,
    val settings: List<Pair<String, String>>,
    /** Próg mowy tej nocy; `null`, gdy filtr był wtedy wyłączony. */
    val speechThreshold: Float?,
) {
    /** Cała noc w kolejności zdarzeń — liczone raz, bo czyta to i oś czasu, i histogram. */
    val allClips: List<ClipEntity> = (clips + discarded).sortedBy { it.startedAt }
}
