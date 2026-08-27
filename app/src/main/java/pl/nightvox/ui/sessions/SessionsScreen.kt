package pl.nightvox.ui.sessions

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.data.db.SessionWithStats
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.ScreenHeader
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (String) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screen)) {
        ScreenHeader(
            title = "Sesje",
            subtitle = if (sessions.isEmpty()) null else "${sessions.size} nocy w archiwum",
        )
        if (sessions.isEmpty()) {
            EmptyState(
                "Jeszcze żadnej nocy.\nUruchom sesję na ekranie „Noc”.",
                icon = Icons.Filled.NightsStay,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenSession(session.id) },
                    )
                }
                item { Spacer(Modifier.height(Spacing.section)) }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionWithStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NightCard(modifier = modifier.clickable(onClick = onClick)) {
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
        Spacer(Modifier.height(Spacing.tiny))
        Text(
            buildString {
                append(Format.shortTime(session.startedAt))
                append(" – ")
                append(session.endedAt?.let { Format.shortTime(it) } ?: "trwa")
                session.endedAt?.let { append("  ·  ${Format.duration(it - session.startedAt)}") }
                append("  ·  tło ${Format.db(session.noiseFloorDb)}")
                if (session.discardedCount > 0) append("  ·  ${session.discardedCount} odrzuconych")
                if (session.interruptions > 0) append("  ·  ${session.interruptions} przerwań")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.endedAt != null && session.clipCount > 0) {
            Spacer(Modifier.height(Spacing.medium))
            Text(
                "łącznie ${Format.duration(session.totalDurationMs)} nagrania",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
