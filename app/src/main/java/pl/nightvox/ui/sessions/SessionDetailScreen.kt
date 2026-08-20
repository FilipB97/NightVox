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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.data.db.ClipEntity
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.ui.components.SectionHeader
import pl.nightvox.ui.components.StatTile
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    viewModel: SessionsViewModel,
    onBack: () -> Unit,
    onOpenClip: (String) -> Unit,
) {
    val context = LocalContext.current
    val detail by viewModel.selected.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var clipFilter by remember { mutableStateOf(SessionClipFilter.KEPT) }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    LaunchedEffect(exported) {
        exported?.let { file ->
            Sharing.shareFile(context, file, "application/zip", "Eksport sesji NightVox")
            viewModel.consumeExport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.let { Format.date(it.session.startedAt) } ?: "Sesja") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    detail?.let { d ->
                        IconButton(onClick = { viewModel.export(d) }) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Eksportuj ZIP")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Usuń sesję")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            EmptyState("Wczytywanie…", Modifier.padding(padding))
            return@Scaffold
        }

        val histogram = remember(current) { NightStats.histogram(current.allClips) }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val endedAt = current.session.endedAt ?: System.currentTimeMillis()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StatTile("klipy", current.clips.size.toString())
                            StatTile("odrzucone", current.discarded.size.toString())
                            StatTile("długość", Format.duration(endedAt - current.session.startedAt))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StatTile("tło", Format.db(current.session.noiseFloorDb))
                            StatTile("przerwania", current.session.interruptions.toString())
                            StatTile(
                                "najdłuższa cisza",
                                Format.duration(
                                    NightStats.longestQuietGapMs(
                                        current.allClips,
                                        current.session.startedAt,
                                        endedAt,
                                    ),
                                ),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        SessionTimeline(
                            startedAt = current.session.startedAt,
                            endedAt = endedAt,
                            marks = remember(current) {
                                current.allClips.map {
                                    TimelineMark(it.startedAt, it.vadScore, it.isDiscarded)
                                }
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Wysokość kreski to ocena mowy; przygaszone to odrzucone. " +
                                "Pionowe linie co godzinę.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                Format.shortTime(current.session.startedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                current.session.endedAt?.let { Format.shortTime(it) } ?: "teraz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        current.session.endReason?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Zakończona: ${endReasonLabel(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (histogram.isNotEmpty()) {
                item { SectionHeader("Rozkład ocen mowy") }
                item {
                    Column {
                        ScoreHistogramView(buckets = histogram, threshold = current.speechThreshold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Dwa skupiska po obu stronach progu znaczą, że filtr widzi dwie różne " +
                                "rzeczy i próg wystarczy przesunąć. Jedna mgła wokół progu znaczy, " +
                                "że sam próg tego nie naprawi." +
                                (
                                    NightStats.medianScore(current.allClips)
                                        ?.let { " Mediana tej nocy: ${Format.score(it)}." } ?: ""
                                    ) +
                                " Eksport (ikona u góry) zawiera klipy.csv z ocenami wszystkich " +
                                "zdarzeń tej nocy, razem z odrzuconymi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionHeader("Zdarzenia (${current.allClips.size})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = clipFilter == SessionClipFilter.KEPT,
                        onClick = { clipFilter = SessionClipFilter.KEPT },
                        label = { Text("Zapisane (${current.clips.size})") },
                    )
                    FilterChip(
                        selected = clipFilter == SessionClipFilter.DISCARDED,
                        onClick = { clipFilter = SessionClipFilter.DISCARDED },
                        label = { Text("Odrzucone (${current.discarded.size})") },
                    )
                    FilterChip(
                        selected = clipFilter == SessionClipFilter.ALL,
                        onClick = { clipFilter = SessionClipFilter.ALL },
                        label = { Text("Wszystkie") },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            val shown = when (clipFilter) {
                SessionClipFilter.KEPT -> current.clips
                SessionClipFilter.DISCARDED -> current.discarded
                SessionClipFilter.ALL -> current.allClips
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        when (clipFilter) {
                            SessionClipFilter.KEPT -> "Cicha noc — nic nie przeszło przez filtr."
                            SessionClipFilter.DISCARDED -> "Tej nocy filtr niczego nie odrzucił."
                            SessionClipFilter.ALL -> "Cicha noc — żadne zdarzenie nie przekroczyło progu."
                        },
                    )
                }
            } else {
                items(shown, key = { it.id }) { clip ->
                    ClipRow(clip = clip, onClick = { onOpenClip(clip.id) })
                }
            }

            if (current.settings.isNotEmpty()) {
                item { SectionHeader("Parametry tej nocy") }
                item {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        current.settings.forEach { (key, value) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć całą sesję?") },
            text = { Text("Skasuje wszystkie klipy tej nocy, łącznie z ulubionymi. Nie da się tego cofnąć.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSession(sessionId)
                    onBack()
                }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") }
            },
        )
    }
}

@Composable
private fun ClipRow(clip: ClipEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.time(clip.startedAt), style = MaterialTheme.typography.bodyLarge)
                if (clip.isDiscarded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "odrzucony",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                buildString {
                    append(Format.clipDuration(clip.durationMs))
                    append(" · nad progiem ${clip.voicedMs} ms")
                    append(" · szczyt ${Format.db(clip.peakDb)}")
                    clip.vadScore?.let { append(" · mowa ${Format.score(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (clip.isFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Ulubiony",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Co pokazać na liście zdarzeń nocy. */
private enum class SessionClipFilter { KEPT, DISCARDED, ALL }

private fun endReasonLabel(reason: String): String = when (reason) {
    "user" -> "ręcznie"
    "auto_stop" -> "auto-stop"
    "no_space" -> "brak miejsca"
    "crash" -> "przerwana (apka padła w nocy)"
    "error" -> "błąd nagrywania"
    else -> reason
}
