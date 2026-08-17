package pl.nightvox.ui.clip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.ui.components.SectionHeader
import pl.nightvox.ui.components.StatTile
import pl.nightvox.ui.components.WaveformView
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    clipId: String,
    viewModel: ClipsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val detail by viewModel.selected.collectAsStateWithLifecycle()
    val playback by viewModel.player.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(clipId) { viewModel.select(clipId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.let { Format.time(it.clip.startedAt) } ?: "Klip") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    detail?.let { d ->
                        IconButton(onClick = { viewModel.toggleFavorite(d.clip) }) {
                            Icon(
                                if (d.clip.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Ulubiony",
                                tint = if (d.clip.isFavorite) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(
                            onClick = { Sharing.shareFile(context, d.file, "audio/mp4", "Udostępnij klip") },
                            enabled = d.file.isFile,
                        ) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Udostępnij")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Usuń")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            EmptyState("Klip nie istnieje albo został usunięty.", Modifier.padding(padding))
            return@Scaffold
        }

        val isThisClip = playback.clipId == current.clip.id
        val durationForProgress = if (isThisClip && playback.durationMs > 0) {
            playback.durationMs.toLong()
        } else {
            current.clip.durationMs
        }
        val progress = if (isThisClip && durationForProgress > 0) {
            playback.positionMs / durationForProgress.toFloat()
        } else {
            0f
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                Format.dateTime(current.clip.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    WaveformView(
                        peaks = current.peaks,
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        onSeek = { fraction -> viewModel.seekFraction(fraction) },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            Format.clipDuration(if (isThisClip) playback.positionMs.toLong() else 0L),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            Format.clipDuration(current.clip.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.playPause(current.clip) },
                            enabled = current.file.isFile,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Icon(
                                if (isThisClip && playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isThisClip && playback.isPlaying) "Pauza" else "Odtwórz",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        playback.error?.takeIf { isThisClip }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }

            SectionHeader("Parametry klipu")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("długość", Format.clipDuration(current.clip.durationMs))
                StatTile("mowa", "${current.clip.voicedMs} ms")
                StatTile("szczyt", Format.db(current.clip.peakDb))
                StatTile("średnia", Format.db(current.clip.meanDb))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Plik: ${current.file.name}" +
                    if (current.file.isFile) " · ${Format.bytes(current.file.length())}" else " · BRAK PLIKU",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Transkrypcja")
            Text(
                current.clip.transcript ?: "Transkrypcja lokalna (whisper.cpp) należy do fazy 3 planu i nie jest jeszcze zaimplementowana.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć klip?") },
            text = { Text("Plik i wpis znikną na zawsze.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    detail?.let { viewModel.delete(it.clip, onDeleted = onBack) }
                }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") }
            },
        )
    }
}
