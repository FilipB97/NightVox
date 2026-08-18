package pl.nightvox.ui.clip

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsScreen(
    viewModel: ClipsViewModel,
    onOpenClip: (String) -> Unit,
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val discardedCount by viewModel.discardedCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Klipy",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                )
                if (filter == ClipFilter.DISCARDED && clips.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearDiscarded) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Opróżnij kosz")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == ClipFilter.ALL,
                    onClick = { viewModel.setFilter(ClipFilter.ALL) },
                    label = { Text("Wszystkie") },
                )
                FilterChip(
                    selected = filter == ClipFilter.FAVORITES,
                    onClick = { viewModel.setFilter(ClipFilter.FAVORITES) },
                    label = { Text("Ulubione") },
                )
                FilterChip(
                    selected = filter == ClipFilter.DISCARDED,
                    onClick = { viewModel.setFilter(ClipFilter.DISCARDED) },
                    label = { Text(if (discardedCount > 0) "Odrzucone ($discardedCount)" else "Odrzucone") },
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (filter == ClipFilter.DISCARDED) {
            Text(
                "Zdarzenia, których bramka nie uznała za mowę. Przejrzyj je przy strojeniu progów — " +
                    "jeśli którekolwiek jest wypowiedzią, próg albo minVoicedMs jest za wysoki.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (clips.isEmpty()) {
            EmptyState(
                when (filter) {
                    ClipFilter.FAVORITES -> "Żaden klip nie jest jeszcze oznaczony jako ulubiony."
                    ClipFilter.DISCARDED -> "Kosz jest pusty — bramka niczego nie odrzuciła."
                    ClipFilter.ALL -> "Brak klipów. Uruchom sesję i prześpij z nią noc."
                },
            )
        } else {
            // Grupowanie po nocach: przy trzydziestu klipach na noc płaska lista przestaje
            // cokolwiek mówić o tym, kiedy się mówiło.
            val nights = remember(clips) { clips.groupBy { Format.date(it.clip.startedAt) } }
            LazyColumn(Modifier.padding(horizontal = 20.dp)) {
                nights.forEach { (night, items) ->
                    item(key = "header-$night") {
                        NightHeader(night = night, count = items.size)
                    }
                    items(items, key = { it.clip.id }) { item ->
                        ClipListRow(
                            item = item,
                            showRestore = filter == ClipFilter.DISCARDED,
                            onClick = { onOpenClip(item.clip.id) },
                            onPlay = { viewModel.playPause(item.clip) },
                            onToggleFavorite = { viewModel.toggleFavorite(item.clip) },
                            onRestore = { viewModel.restore(item.clip) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        SnackbarHost(snackbarHostState)
    }
}

@Composable
private fun NightHeader(night: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            night,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClipListRow(
    item: ClipListItem,
    showRestore: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay, enabled = item.fileExists) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Odtwórz")
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(Format.time(item.clip.startedAt), style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(Format.clipDuration(item.clip.durationMs))
                    append(" · szczyt ${Format.db(item.clip.peakDb)}")
                    append(" · nad progiem ${item.clip.voicedMs} ms")
                    item.clip.vadScore?.let {
                        append(" · mowa ${String.format(java.util.Locale.US, "%.2f", it)}")
                    }
                    if (item.sizeBytes > 0) append(" · ${Format.bytes(item.sizeBytes)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.fileExists) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = "Brak pliku",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        if (showRestore) {
            IconButton(onClick = onRestore) {
                Icon(Icons.Filled.Restore, contentDescription = "Przywróć")
            }
        } else {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (item.clip.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Ulubiony",
                    tint = if (item.clip.isFavorite) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
