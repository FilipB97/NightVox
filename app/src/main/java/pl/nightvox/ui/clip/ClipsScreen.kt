package pl.nightvox.ui.clip

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.util.Format

@Composable
fun ClipsScreen(
    viewModel: ClipsViewModel,
    onOpenClip: (String) -> Unit,
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "Klipy",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { viewModel.toggleFavoritesFilter() },
                label = { Text("Tylko ulubione") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                    )
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        if (clips.isEmpty()) {
            EmptyState(
                if (favoritesOnly) {
                    "Żaden klip nie jest jeszcze oznaczony jako ulubiony."
                } else {
                    "Brak klipów. Uruchom sesję i prześpij z nią noc."
                },
            )
        } else {
            LazyColumn {
                items(clips, key = { it.clip.id }) { item ->
                    ClipListRow(
                        item = item,
                        onClick = { onOpenClip(item.clip.id) },
                        onPlay = { viewModel.playPause(item.clip) },
                        onToggleFavorite = { viewModel.toggleFavorite(item.clip) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ClipListRow(
    item: ClipListItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
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
            Text(Format.dateTime(item.clip.startedAt), style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(Format.clipDuration(item.clip.durationMs))
                    append(" · szczyt ${Format.db(item.clip.peakDb)}")
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
