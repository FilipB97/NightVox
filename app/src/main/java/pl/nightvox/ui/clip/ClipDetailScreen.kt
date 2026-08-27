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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.SectionHeader
import pl.nightvox.ui.components.StatTile
import pl.nightvox.ui.components.WaveformView
import pl.nightvox.ui.components.clipPlayKey
import pl.nightvox.ui.components.rememberHaptics
import pl.nightvox.ui.components.sharedWith
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    clipId: String,
    viewModel: ClipsViewModel,
    onBack: () -> Unit,
    onOpenClip: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val detail by viewModel.selected.collectAsStateWithLifecycle()
    val playback by viewModel.player.state.collectAsStateWithLifecycle()
    val neighbours by viewModel.neighbours.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
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
                        IconButton(onClick = { haptics.tick(); viewModel.toggleFavorite(d.clip) }) {
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
                        IconButton(onClick = { haptics.reject(); confirmDelete = true }) {
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
                .padding(horizontal = Spacing.screen),
        ) {
            Text(
                Format.dateTime(current.clip.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (current.clip.isDiscarded) {
                Spacer(Modifier.height(12.dp))
                NoticeCard(
                    icon = Icons.Filled.FilterAlt,
                    title = "Odrzucony przez bramkę",
                    text = when (current.clip.discardReason) {
                        "TOO_SHORT" -> "Sumaryczny czas ramek powyżej progu (${current.clip.voicedMs} ms) " +
                            "nie osiągnął minVoicedMs. Jeśli to jednak wypowiedź — obniż minVoicedMs " +
                            "albo próg i przywróć klip."
                        "NOT_SPEECH" -> "Bramka przepuściła, ale analiza widmowa nie znalazła tu struktury " +
                            "mowy — ocena ${current.clip.vadScore?.let { Format.score(it) } ?: "—"}. " +
                            "Tak wygląda oddech (szum szerokopasmowy bez okresowości) i chrapanie " +
                            "(buczenie poniżej 90 Hz). Odsłuchaj: jeśli to jednak wypowiedź, obniż próg mowy " +
                            "w ustawieniach i przywróć klip."
                        "MANUAL" -> "Odłożony do kosza ręcznie, gestem na liście. Plik został — " +
                            "kosz ma własną, krótszą retencję, więc jest jeszcze czas, żeby zmienić zdanie."
                        "LOW_VAD" -> "Bramka przepuściła, ale Silero VAD nie uznał tego za mowę " +
                            "(${current.clip.vadScore?.let { Format.score(it) } ?: "—"}). " +
                            "Wpis z wcześniejszej wersji aplikacji."
                        else -> "Powód: ${current.clip.discardReason ?: "nieznany"}"
                    },
                    tone = NoticeTone.WARNING,
                    actionLabel = "Przywróć do klipów",
                    onAction = { haptics.confirm(); viewModel.restore(current.clip) },
                )
            }

            Spacer(Modifier.height(20.dp))

            NightCard {
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
                        onClick = { haptics.tick(); viewModel.playPause(current.clip) },
                        enabled = current.file.isFile,
                        shape = CircleShape,
                        modifier = Modifier
                            .sharedWith(clipPlayKey(current.clip.id))
                            .size(64.dp),
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

            // Klip otwarty z ekranu sesji może nie być w bieżącej liście „Klipy" (np. odrzucony
            // przy filtrze „Wszystkie"). Wtedy nie ma czego numerować i pasek się nie pokazuje.
            neighbours?.takeIf { it.position > 0 && it.total > 1 }?.let { position ->
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { haptics.tick(); position.previousId?.let(onOpenClip) },
                        enabled = position.previousId != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Poprzedni")
                    }
                    Text(
                        if (position.position > 0) "${position.position} / ${position.total}" else "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { haptics.tick(); position.nextId?.let(onOpenClip) },
                        enabled = position.nextId != null,
                    ) {
                        Text("Następny")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }

            SectionHeader("Parametry klipu")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("długość", Format.clipDuration(current.clip.durationMs))
                StatTile("nad progiem", "${current.clip.voicedMs} ms")
                StatTile("szczyt", Format.db(current.clip.peakDb))
                StatTile(
                    "mowa",
                    current.clip.vadScore?.let { Format.score(it) } ?: "—",
                )
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
