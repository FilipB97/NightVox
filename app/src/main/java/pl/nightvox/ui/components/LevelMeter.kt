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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import pl.nightvox.audio.LevelMeter
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format

private const val MIN_DB = LevelMeter.MIN_DBFS
private const val MAX_DB = 0f

/**
 * Live meter: historia poziomu + linia tła + linia progu.
 *
 * Historia przychodzi gotowa z ViewModelu — Compose nie ma tu nic mutować w trakcie
 * kompozycji. Odświeżanie ~10 Hz, nie 50: przy 50 Hz przez 8 h rysowanie kosztuje więcej
 * niż cały pipeline audio, a oko i tak nie widzi różnicy.
 *
 * Wypełniona powierzchnia z gradientem zamiast setki pionowych kresek: przy 120 słupkach na
 * 360 px kreski zlewały się w szarą ścianę i nie było widać, gdzie właściwie jest poziom.
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
    val plotBackground = MaterialTheme.colorScheme.surfaceContainerLowest
    val floorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val thresholdColor = MaterialTheme.colorScheme.tertiary
    val activeColor = MaterialTheme.colorScheme.error
    val idleColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f)
    val traceColor = if (isRecording) activeColor else idleColor

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(plotBackground),
        ) {
            fun yFor(db: Float): Float {
                val clamped = db.coerceIn(MIN_DB, MAX_DB)
                return size.height * (1f - (clamped - MIN_DB) / (MAX_DB - MIN_DB))
            }

            // Siatka co 20 dB — bez niej wykres nie mówi nic o skali.
            var grid = MIN_DB + 10f
            while (grid <= MAX_DB) {
                val y = yFor(grid)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                grid += 20f
            }

            if (history.size >= 2) {
                val slots = capacity.coerceAtLeast(2)
                val step = size.width / (slots - 1)
                val offset = (slots - history.size).coerceAtLeast(0)
                val firstX = offset * step

                val line = Path().apply {
                    history.forEachIndexed { index, db ->
                        val x = (offset + index) * step
                        if (index == 0) moveTo(x, yFor(db)) else lineTo(x, yFor(db))
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo((offset + history.size - 1) * step, size.height)
                    lineTo(firstX, size.height)
                    close()
                }

                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(traceColor.copy(alpha = 0.32f), traceColor.copy(alpha = 0.02f)),
                        startY = 0f,
                        endY = size.height,
                    ),
                )
                drawPath(
                    path = line,
                    color = traceColor,
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }

            drawLine(
                color = floorColor,
                start = Offset(0f, yFor(floorDb)),
                end = Offset(size.width, yFor(floorDb)),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)),
            )
            drawLine(
                color = thresholdColor,
                start = Offset(0f, yFor(thresholdDb)),
                end = Offset(size.width, yFor(thresholdDb)),
                strokeWidth = 2f,
            )
        }

        Spacer(Modifier.height(Spacing.medium))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MeterLegend("poziom", Format.db(levelDb), traceColor)
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
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(Spacing.small))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.labelMedium)
    }
}
