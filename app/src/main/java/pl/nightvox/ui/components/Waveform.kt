package pl.nightvox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Obwiednia klipu z pozycją odtwarzania. Rysowana z tablicy peaków policzonej przy
 * zapisie (`.peaks` obok pliku) — żadnego ponownego dekodowania audio.
 */
@Composable
fun WaveformView(
    peaks: ByteArray?,
    progress: Float,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    Canvas(
        modifier = modifier.then(
            if (onSeek != null) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            } else {
                Modifier
            },
        ),
    ) {
        val centerY = size.height / 2f
        if (peaks == null || peaks.isEmpty()) {
            drawLine(
                color = emptyColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 2f,
            )
            return@Canvas
        }

        // Więcej próbek niż pikseli: składamy je w kolumny, biorąc maksimum z kubełka.
        val columns = (size.width / COLUMN_WIDTH_PX).toInt().coerceIn(1, peaks.size)
        val perColumn = peaks.size.toFloat() / columns
        val playedColumns = (columns * progress.coerceIn(0f, 1f))

        for (column in 0 until columns) {
            val from = (column * perColumn).toInt()
            val to = ((column + 1) * perColumn).toInt().coerceAtMost(peaks.size)
            var maxValue = 0
            for (i in from until maxOf(to, from + 1).coerceAtMost(peaks.size)) {
                val v = peaks[i].toInt() and 0xFF
                if (v > maxValue) maxValue = v
            }
            val amplitude = (maxValue / 255f).coerceAtLeast(0.02f) * centerY
            val x = column * (size.width / columns) + (size.width / columns) / 2f
            drawLine(
                color = if (column < playedColumns) playedColor else remainingColor,
                start = Offset(x, centerY - amplitude),
                end = Offset(x, centerY + amplitude),
                strokeWidth = (size.width / columns) * 0.55f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val COLUMN_WIDTH_PX = 6f
