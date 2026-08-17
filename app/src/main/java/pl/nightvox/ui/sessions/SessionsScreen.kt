package pl.nightvox.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.data.db.SessionWithStats
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.util.Format

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (String) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "Sesje",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        if (sessions.isEmpty()) {
            EmptyState("Jeszcze żadnej nocy. Uruchom sesję na ekranie „Noc”.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session = session, onClick = { onOpenSession(session.id) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionWithStats, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(Format.date(session.startedAt), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${session.clipCount} klip.",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(Format.shortTime(session.startedAt))
                    append(" – ")
                    append(session.endedAt?.let { Format.shortTime(it) } ?: "trwa")
                    session.endedAt?.let { append("  ·  ${Format.duration(it - session.startedAt)}") }
                    append("  ·  tło ${Format.db(session.noiseFloorDb)}")
                    if (session.interruptions > 0) append("  ·  ${session.interruptions} przerwań")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.endedAt != null && session.clipCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "łącznie ${Format.duration(session.totalDurationMs)} nagrania",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Mini-oś czasu nocy: każdy klip to kreska w miejscu, w którym padł.
 * Od jednego spojrzenia widać, czy gadanie skupia się nad ranem, czy leci całą noc.
 */
@Composable
fun SessionTimeline(
    startedAt: Long,
    endedAt: Long,
    clipTimes: List<Long>,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val markColor = MaterialTheme.colorScheme.primary
    val span = (endedAt - startedAt).coerceAtLeast(1)

    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        drawRect(color = trackColor, size = size)
        for (time in clipTimes) {
            val fraction = ((time - startedAt).toFloat() / span).coerceIn(0f, 1f)
            val x = fraction * size.width
            drawLine(
                color = markColor,
                start = Offset(x, size.height * 0.15f),
                end = Offset(x, size.height * 0.85f),
                strokeWidth = 3f,
            )
        }
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1f,
        )
    }
}
