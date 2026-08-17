package pl.nightvox.ui.clip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.nightvox.AppContainer
import pl.nightvox.data.db.ClipEntity
import pl.nightvox.encode.Waveform
import java.io.File

data class ClipListItem(
    val clip: ClipEntity,
    val fileExists: Boolean,
    val sizeBytes: Long,
)

data class ClipDetail(
    val clip: ClipEntity,
    val file: File,
    val peaks: ByteArray?,
) {
    // ByteArray w data class — porównanie po referencji tablicy wystarczy, bo instancja
    // jest tworzona raz na wczytanie klipu. equals/hashCode nadpisane, żeby nie zmylić.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ClipDetail && other.clip == clip && other.file == file)

    override fun hashCode(): Int = 31 * clip.hashCode() + file.hashCode()
}

class ClipsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.clipRepository
    val player = ClipPlayer()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val clips: StateFlow<List<ClipListItem>> =
        combine(repository.clips, _showFavoritesOnly) { all, favoritesOnly ->
            all.filter { !favoritesOnly || it.isFavorite }
                .map { clip ->
                    val file = File(clip.filePath)
                    ClipListItem(clip, file.isFile, file.length())
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selected: StateFlow<ClipDetail?> = selectedId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repository.observeClip(id).map { clip ->
                clip?.let {
                    val file = File(it.filePath)
                    ClipDetail(it, file, Waveform.read(file))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // MediaPlayer nie ma strumienia pozycji — odpytujemy go w rytmie UI.
        viewModelScope.launch {
            while (true) {
                delay(POSITION_POLL_MS)
                player.refreshPosition()
            }
        }
    }

    fun select(clipId: String) {
        if (selectedId.value != clipId) player.release()
        selectedId.value = clipId
    }

    fun toggleFavoritesFilter() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    fun toggleFavorite(clip: ClipEntity) {
        viewModelScope.launch { repository.setFavorite(clip.id, !clip.isFavorite) }
    }

    fun playPause(clip: ClipEntity) {
        player.toggle(clip.id, File(clip.filePath))
    }

    fun seekFraction(fraction: Float) {
        val duration = player.state.value.durationMs
        if (duration > 0) player.seekTo((duration * fraction).toInt())
    }

    fun delete(clip: ClipEntity, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            if (player.state.value.clipId == clip.id) player.release()
            repository.deleteClip(clip)
            _message.value = "Klip usunięty"
            onDeleted()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        private const val POSITION_POLL_MS = 100L
    }
}
