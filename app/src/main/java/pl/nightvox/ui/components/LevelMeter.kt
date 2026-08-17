package pl.nightvox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import pl.nightvox.audio.LevelMeter
import pl.nightvox.util.Format

private const val MIN_DB = LevelMeter.MIN_DBFS
private const val MAX_DB = 0f

/**
 * Live meter: historia poziomu + linia tła + linia progu.
 *
 * Historia przychodzi gotowa z ViewModelu — Compose nie ma tu nic mutować w trakcie
 * kompozycji. Odświeżanie ~10 Hz, nie 50: przy 50 Hz przez 8 h rysowanie kosztuje więcej
 * niż cały pipeline audio, a oko i tak nie widzi różnicy.
 */
@Composable
fun LiveLevelMeter(
    history: List<Float>,
    levelDb: Float,
    floorDb: Float,
    thresholdDb: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    capacity: Int = 120,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val floorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val thresholdColor = MaterialTheme.colorScheme.tertiary
    val activeColor = MaterialTheme.colorScheme.error
    val idleColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            fun yFor(db: Float): Float {
                val clamped = db.coerceIn(MIN_DB, MAX_DB)
                return size.height * (1f - (clamped - MIN_DB) / (MAX_DB - MIN_DB))
            }

            drawRect(color = surface, size = size)

            // Siatka co 20 dB — bez niej wykres nie mówi nic o skali.
            var grid = MIN_DB + 10f
            while (grid <= MAX_DB) {
                val y = yFor(grid)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                grid += 20f
            }

            if (history.isNotEmpty()) {
                val slots = capacity.coerceAtLeast(2)
                val step = size.width / (slots - 1)
                val barColor = (if (isRecording) activeColor else idleColor).copy(alpha = 0.85f)
                val offset = (slots - history.size).coerceAtLeast(0)
                for (index in history.indices) {
                    val x = (offset + index) * step
                    drawLine(
                        color = barColor,
                        start = Offset(x, size.height),
                        end = Offset(x, yFor(history[index])),
                        strokeWidth = step.coerceAtLeast(1.5f),
                    )
                }
            }

            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawLine(
                color = floorColor,
                start = Offset(0f, yFor(floorDb)),
                end = Offset(size.width, yFor(floorDb)),
                strokeWidth = 2f,
                pathEffect = dash,
            )
            drawLine(
                color = thresholdColor,
                start = Offset(0f, yFor(thresholdDb)),
                end = Offset(size.width, yFor(thresholdDb)),
                strokeWidth = 2.5f,
            )
            drawRect(color = gridColor, size = size, style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MeterLegend("poziom", Format.db(levelDb), if (isRecording) activeColor else idleColor)
            MeterLegend("tło", Format.db(floorDb), floorColor)
            MeterLegend("próg", Format.db(thresholdDb), thresholdColor)
        }
    }
}

@Composable
private fun MeterLegend(label: String, value: String, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
