package pl.nightvox.ui.clip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
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

enum class ClipFilter { ALL, FAVORITES, DISCARDED }

/**
 * Kolejność listy. [SCORE] jest tu po to, żeby przegląd nocy nie polegał na przesłuchaniu
 * stu klipów po kolei: przy „Wszystkie” wypycha na górę to, co najbardziej przypomina mowę,
 * a w koszu — to, co filtr odrzucił najmniej pewnie, czyli dokładnie te klipy, przy których
 * może się mylić.
 */
enum class ClipSort { NEWEST, SCORE }

/** Sąsiedzi klipu w bieżącej liście — do przechodzenia „dalej” bez wracania do listy. */
data class ClipNeighbours(
    val previousId: String?,
    val nextId: String?,
    /** Pozycja licząc od 1; 0, gdy klipu nie ma w bieżącej liście. */
    val position: Int,
    val total: Int,
)

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

    private val _filter = MutableStateFlow(ClipFilter.ALL)
    val filter: StateFlow<ClipFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(ClipSort.NEWEST)
    val sort: StateFlow<ClipSort> = _sort.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val clips: StateFlow<List<ClipListItem>> = combine(_filter, _sort) { filter, sort -> filter to sort }
        .flatMapLatest { (filter, sort) ->
            val source = when (filter) {
                ClipFilter.ALL -> repository.clips
                ClipFilter.FAVORITES -> repository.favorites
                ClipFilter.DISCARDED -> repository.discarded
            }
            source.map { list ->
                list.sortedWith(comparatorFor(sort)).map { clip ->
                    val file = File(clip.filePath)
                    ClipListItem(clip, file.isFile, file.length())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val discardedCount: StateFlow<Int> = repository.discarded
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    /** Gdzie jesteśmy w bieżącej liście — żeby ze szczegółów klipu iść dalej, a nie wstecz. */
    val neighbours: StateFlow<ClipNeighbours?> = combine(clips, selectedId) { list, id ->
        if (id == null) return@combine null
        val index = list.indexOfFirst { it.clip.id == id }
        if (index < 0) {
            ClipNeighbours(null, null, 0, list.size)
        } else {
            ClipNeighbours(
                previousId = list.getOrNull(index - 1)?.clip?.id,
                nextId = list.getOrNull(index + 1)?.clip?.id,
                position = index + 1,
                total = list.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun setFilter(filter: ClipFilter) {
        _filter.value = filter
    }

    fun setSort(sort: ClipSort) {
        _sort.value = sort
    }

    /** Klipy bez oceny (nagrane przed filtrem mowy) idą na koniec, nie na początek. */
    private fun comparatorFor(sort: ClipSort): Comparator<ClipEntity> = when (sort) {
        ClipSort.NEWEST -> compareByDescending { it.startedAt }
        ClipSort.SCORE -> compareByDescending<ClipEntity> { it.vadScore ?: -1f }
            .thenByDescending { it.startedAt }
    }

    /** Odrzucony klip okazał się mową — wraca na zwykłą listę. */
    fun restore(clip: ClipEntity) {
        viewModelScope.launch {
            repository.restoreDiscarded(clip)
            _message.value = "Klip przywrócony"
        }
    }

    /** Gest na liście: klip do kosza, bez kasowania pliku — da się go potem przywrócić. */
    fun moveToTrash(clip: ClipEntity) {
        viewModelScope.launch {
            repository.moveToDiscarded(clip)
            _message.value = "Klip w koszu"
        }
    }

    fun clearDiscarded() {
        viewModelScope.launch {
            val deleted = repository.clearDiscarded()
            _message.value = if (deleted == 0) "Kosz był pusty" else "Usunięto $deleted odrzuconych klipów"
        }
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
