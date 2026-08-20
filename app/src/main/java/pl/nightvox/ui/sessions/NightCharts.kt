package pl.nightvox.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Jedno zdarzenie na osi czasu nocy. [score] `null` = klip sprzed filtru mowy. */
data class TimelineMark(
    val atMs: Long,
    val score: Float?,
    val discarded: Boolean,
)

/**
 * Oś czasu nocy: kiedy padły zdarzenia, które przeszły, a które filtr odrzucił.
 *
 * Wysokość kreski to ocena mowy, więc przy stu zdarzeniach od razu widać, czy wszystkie
 * były jednakowo nijakie (oddech przez całą noc), czy któreś odstaje. Odrzucone rysujemy
 * przygaszone i **pod** zapisanymi — kosz ma być widoczny, ale nie ma zasłaniać tego, co
 * zostało.
 */
@Composable
fun SessionTimeline(
    startedAt: Long,
    endedAt: Long,
    marks: List<TimelineMark>,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val keptColor = MaterialTheme.colorScheme.primary
    val discardedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val hourColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val span = (endedAt - startedAt).coerceAtLeast(1)

    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        drawRect(color = trackColor, size = size)

        // Kreski co pełną godzinę — bez nich „gdzieś w środku nocy” nie da się odczytać.
        var hour = ((startedAt / HOUR_MS) + 1) * HOUR_MS
        while (hour < endedAt) {
            val x = ((hour - startedAt).toFloat() / span) * size.width
            drawLine(hourColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            hour += HOUR_MS
        }

        // Odrzucone najpierw, żeby zapisane rysowały się na wierzchu.
        for (pass in 0..1) {
            for (mark in marks) {
                if ((pass == 0) != mark.discarded) continue
                val fraction = ((mark.atMs - startedAt).toFloat() / span).coerceIn(0f, 1f)
                val x = fraction * size.width
                val weight = mark.score?.let { 0.3f + 0.7f * it.coerceIn(0f, 1f) } ?: 1f
                val half = size.height * 0.42f * weight
                drawLine(
                    color = if (mark.discarded) discardedColor else keptColor,
                    start = Offset(x, size.height / 2 - half),
                    end = Offset(x, size.height / 2 + half),
                    strokeWidth = if (mark.discarded) 2f else 3f,
                )
            }
        }

        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1f,
        )
    }
}

/**
 * Rozkład ocen mowy w nocy.
 *
 * Przy stu klipach pojedyncze liczby nic nie mówią, a rozkład odpowiada na jedyne istotne
 * pytanie o strojenie: czy filtr widzi **dwie różne rzeczy** (dwa skupiska po obu stronach
 * progu), czy jedną mgłę rozłożoną wokół niego. W drugim przypadku przesuwanie progu
 * niczego nie naprawi.
 */
@Composable
fun ScoreHistogramView(
    buckets: List<ScoreBucket>,
    threshold: Float?,
    modifier: Modifier = Modifier,
) {
    if (buckets.isEmpty()) return
    val keptColor = MaterialTheme.colorScheme.primary
    val discardedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val thresholdColor = MaterialTheme.colorScheme.tertiary
    val max = buckets.maxOf { it.total }.coerceAtLeast(1)

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / buckets.size
            val gap = slot * 0.18f
            buckets.forEachIndexed { index, bucket ->
                val left = index * slot + gap / 2
                val width = slot - gap
                var bottom = size.height
                for ((count, color) in listOf(bucket.discarded to discardedColor, bucket.kept to keptColor)) {
                    if (count == 0) continue
                    val height = size.height * count / max
                    drawRect(
                        color = color,
                        topLeft = Offset(left, bottom - height),
                        size = Size(width, height),
                    )
                    bottom -= height
                }
            }
            threshold?.let {
                val x = it.coerceIn(0f, 1f) * size.width
                drawLine(thresholdColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0,0", style = MaterialTheme.typography.labelSmall)
            Text("ocena mowy", style = MaterialTheme.typography.labelSmall)
            Text("1,0", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val HOUR_MS = 3_600_000L
