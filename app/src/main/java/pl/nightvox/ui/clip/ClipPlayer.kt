package pl.nightvox.ui.clip

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlaybackState(
    val clipId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val error: String? = null,
)

/**
 * Odtwarzanie klipów. `MediaPlayer` wystarczy: pliki są krótkie, lokalne i jednościeżkowe —
 * ExoPlayer dołożyłby kilka MB do APK i nic poza tym.
 */
class ClipPlayer {

    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun toggle(clipId: String, file: File) {
        if (_state.value.clipId == clipId && player != null) {
            if (_state.value.isPlaying) pause() else resume()
        } else {
            play(clipId, file)
        }
    }

    fun play(clipId: String, file: File) {
        release()
        if (!file.isFile) {
            _state.value = PlaybackState(clipId = clipId, error = "Plik klipu nie istnieje")
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPlaying = false, positionMs = 0)
                    seekTo(0)
                }
                start()
            }
            player = mp
            _state.value = PlaybackState(
                clipId = clipId,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się odtworzyć ${file.name}", e)
            _state.value = PlaybackState(clipId = clipId, error = "Nie udało się odtworzyć klipu")
        }
    }

    fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun resume() {
        player?.start()
        _state.value = _state.value.copy(isPlaying = true)
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms)
        _state.value = _state.value.copy(positionMs = ms)
    }

    /** Wołane z pętli UI ~10 Hz — MediaPlayer nie ma własnego strumienia pozycji. */
    fun refreshPosition() {
        val mp = player ?: return
        if (_state.value.isPlaying) {
            _state.value = _state.value.copy(positionMs = runCatching { mp.currentPosition }.getOrDefault(0))
        }
    }

    fun release() {
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        _state.value = PlaybackState()
    }

    companion object {
        private const val TAG = "NightVox/Player"
    }
}
