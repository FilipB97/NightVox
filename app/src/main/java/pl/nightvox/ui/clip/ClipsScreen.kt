package pl.nightvox.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.ui.components.ScreenHeader
import pl.nightvox.ui.components.clipPlayKey
import pl.nightvox.ui.components.rememberHaptics
import pl.nightvox.ui.components.sharedWith
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsScreen(
    viewModel: ClipsViewModel,
    onOpenClip: (String) -> Unit,
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val discardedCount by viewModel.discardedCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberHaptics()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Spacing.screen)) {
            ScreenHeader(
                title = "Klipy",
                subtitle = when (filter) {
                    ClipFilter.ALL -> "${clips.size} nagrań"
                    ClipFilter.FAVORITES -> "${clips.size} ulubionych"
                    ClipFilter.DISCARDED -> "${clips.size} w koszu"
                },
                trailing = {
                    if (filter == ClipFilter.DISCARDED && clips.isNotEmpty()) {
                        IconButton(onClick = { haptics.reject(); viewModel.clearDiscarded() }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Opróżnij kosz")
                        }
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                FilterChip(
                    selected = filter == ClipFilter.ALL,
                    onClick = { haptics.tick(); viewModel.setFilter(ClipFilter.ALL) },
                    label = { Text("Wszystkie") },
                )
                FilterChip(
                    selected = filter == ClipFilter.FAVORITES,
                    onClick = { haptics.tick(); viewModel.setFilter(ClipFilter.FAVORITES) },
                    label = { Text("Ulubione") },
                )
                FilterChip(
                    selected = filter == ClipFilter.DISCARDED,
                    onClick = { haptics.tick(); viewModel.setFilter(ClipFilter.DISCARDED) },
                    label = { Text(if (discardedCount > 0) "Odrzucone ($discardedCount)" else "Odrzucone") },
                )
            }
            Spacer(Modifier.height(Spacing.small))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    "KOLEJNOŚĆ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = sort == ClipSort.NEWEST,
                    onClick = { haptics.tick(); viewModel.setSort(ClipSort.NEWEST) },
                    label = { Text("Najnowsze") },
                )
                FilterChip(
                    selected = sort == ClipSort.SCORE,
                    onClick = { haptics.tick(); viewModel.setSort(ClipSort.SCORE) },
                    label = { Text("Wg oceny mowy") },
                )
            }
        }

        if (filter == ClipFilter.DISCARDED) {
            Text(
                "Zdarzenia, których bramka albo filtr mowy nie uznały za mowę. Ustaw kolejność wg oceny — " +
                    "na górze wylądują te, przy których filtr był najbliżej pomyłki. Jeśli któreś jest " +
                    "wypowiedzią, obniż próg mowy albo minVoicedMs i przywróć klip.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.medium),
            )
        } else {
            Spacer(Modifier.height(Spacing.small))
        }

        if (clips.isEmpty()) {
            EmptyState(
                text = when (filter) {
                    ClipFilter.FAVORITES -> "Żaden klip nie jest jeszcze oznaczony jako ulubiony."
                    ClipFilter.DISCARDED -> "Kosz jest pusty — ani bramka, ani filtr mowy niczego nie odrzuciły."
                    ClipFilter.ALL -> "Brak klipów.\nUruchom sesję i prześpij z nią noc."
                },
                icon = when (filter) {
                    ClipFilter.FAVORITES -> Icons.Filled.StarBorder
                    ClipFilter.DISCARDED -> Icons.Filled.DeleteSweep
                    ClipFilter.ALL -> Icons.Filled.GraphicEq
                },
            )
        } else {
            // Grupowanie po nocach: przy trzydziestu klipach na noc płaska lista przestaje
            // cokolwiek mówić o tym, kiedy się mówiło. Przy sortowaniu wg oceny nagłówki nocy
            // byłyby kłamstwem — kolejność nie ma wtedy nic wspólnego z czasem.
            val nights = remember(clips, sort) {
                if (sort == ClipSort.NEWEST) clips.groupBy { Format.date(it.clip.startedAt) } else null
            }
            LazyColumn(Modifier.padding(horizontal = Spacing.screen)) {
                if (nights != null) {
                    nights.forEach { (night, items) ->
                        item(key = "header-$night") {
                            NightHeader(night = night, count = items.size)
                        }
                        items(items, key = { it.clip.id }) { item ->
                            SwipeableClipRow(
                                modifier = Modifier.animateItem(),
                                item = item,
                                inTrash = filter == ClipFilter.DISCARDED,
                                showDate = false,
                                viewModel = viewModel,
                                onOpenClip = onOpenClip,
                            )
                        }
                    }
                } else {
                    items(clips, key = { it.clip.id }) { item ->
                        SwipeableClipRow(
                            modifier = Modifier.animateItem(),
                            item = item,
                            inTrash = filter == ClipFilter.DISCARDED,
                            showDate = true,
                            viewModel = viewModel,
                            onOpenClip = onOpenClip,
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

/**
 * Wiersz klipu z gestami.
 *
 * Przegląd nocy to sto klipów, a dotąd każda decyzja wymagała wejścia w szczegóły i powrotu.
 * W bok: ulubione albo do kosza — jedno i drugie odwracalne, bo kosz trzyma plik przez kilka
 * dni. Nic tu nie kasuje nieodwracalnie; na to jest osobny przycisk w szczegółach klipu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableClipRow(
    item: ClipListItem,
    inTrash: Boolean,
    showDate: Boolean,
    viewModel: ClipsViewModel,
    onOpenClip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.tick()
                    viewModel.toggleFavorite(item.clip)
                    // false = wiersz wraca na miejsce; gwiazdka to przełącznik, nie usunięcie.
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    if (inTrash) {
                        haptics.confirm()
                        viewModel.restore(item.clip)
                    } else {
                        haptics.reject()
                        viewModel.moveToTrash(item.clip)
                    }
                    // true = wiersz znika, bo i tak wypada z bieżącej listy.
                    true
                }

                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = !inTrash,
        backgroundContent = { SwipeBackground(state.dismissDirection, inTrash, item.clip.isFavorite) },
    ) {
        ClipListRow(
            // Tło pod wierszem, żeby spod niego nie prześwitywała warstwa gestu.
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            item = item,
            showRestore = inTrash,
            showDate = showDate,
            onClick = { onOpenClip(item.clip.id) },
            onPlay = { viewModel.playPause(item.clip) },
            onToggleFavorite = { haptics.tick(); viewModel.toggleFavorite(item.clip) },
            onRestore = { haptics.confirm(); viewModel.restore(item.clip) },
        )
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, inTrash: Boolean, isFavorite: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (container, icon, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeLook(
            scheme.tertiaryContainer,
            if (isFavorite) Icons.Filled.StarBorder else Icons.Filled.Star,
            if (isFavorite) "Usuń z ulubionych" else "Ulubione",
            Alignment.CenterStart,
        )

        SwipeToDismissBoxValue.EndToStart -> if (inTrash) {
            SwipeLook(scheme.secondaryContainer, Icons.Filled.Restore, "Przywróć", Alignment.CenterEnd)
        } else {
            SwipeLook(scheme.errorContainer, Icons.Filled.DeleteSweep, "Do kosza", Alignment.CenterEnd)
        }

        SwipeToDismissBoxValue.Settled -> SwipeLook(scheme.background, null, "", Alignment.Center)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(horizontal = Spacing.screen),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.small))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private data class SwipeLook(
    val container: Color,
    val icon: ImageVector?,
    val label: String,
    val alignment: Alignment,
)

@Composable
private fun ClipListRow(
    modifier: Modifier = Modifier,
    item: ClipListItem,
    showRestore: Boolean,
    /** Przy sortowaniu wg oceny nie ma nagłówków nocy, więc data musi być w wierszu. */
    showDate: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.small, horizontal = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayDisc(clipId = item.clip.id, enabled = item.fileExists, onClick = onPlay)

        Column(Modifier.weight(1f).padding(start = Spacing.medium)) {
            ClipRowTitle(item = item, showDate = showDate)
            Spacer(Modifier.height(2.dp))
            Text(
                text = clipMeta(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item.clip.vadScore?.let { ScorePill(it) }

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

@Composable
private fun PlayDisc(clipId: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .sharedWith(clipPlayKey(clipId))
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Odtwórz",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ClipRowTitle(item: ClipListItem, showDate: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Format.time(item.clip.startedAt), style = MaterialTheme.typography.bodyLarge)
        if (showDate) {
            Spacer(Modifier.width(Spacing.small))
            Text(
                Format.date(item.clip.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.fileExists) {
            Spacer(Modifier.width(Spacing.small))
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = "Brak pliku",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun clipMeta(item: ClipListItem): String = buildString {
    append(Format.clipDuration(item.clip.durationMs))
    append(" · szczyt ")
    append(Format.db(item.clip.peakDb))
    append(" · nad progiem ")
    append(item.clip.voicedMs)
    append(" ms")
    if (item.sizeBytes > 0) {
        append(" · ")
        append(Format.bytes(item.sizeBytes))
    }
}

/**
 * Ocena mowy jako pastylka. Kolor niesie tę samą informację co liczba, więc przy przewijaniu
 * stu klipów widać rozkład bez czytania — a to jest cały sens tego ekranu przy strojeniu.
 */
@Composable
private fun ScorePill(score: Float) {
    val scheme = MaterialTheme.colorScheme
    val strong = score >= 0.5f
    Text(
        text = Format.score(score),
        style = MaterialTheme.typography.labelMedium,
        color = if (strong) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (strong) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.small, vertical = Spacing.tiny),
    )
}
