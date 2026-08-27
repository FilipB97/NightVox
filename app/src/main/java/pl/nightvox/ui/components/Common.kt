package pl.nightvox.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.nightvox.ui.theme.Spacing

/**
 * Jedyny pojemnik na treść w całej aplikacji.
 *
 * Wcześniej każdy ekran wołał `Card(colors = ...)` z własnym odcieniem tła i własnym
 * paddingiem, więc karty na sąsiednich ekranach nie były tą samą rzeczą. Tutaj jest jedna
 * decyzja: kontener o jeden stopień jaśniejszy od tła, bez cienia, zaokrąglenie z motywu.
 */
@Composable
fun NightCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    padding: androidx.compose.ui.unit.Dp = Spacing.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * Nagłówek ekranu: nazwa i jedno zdanie kontekstu.
 *
 * Cztery ekrany miały cztery różne tytuły o różnych stylach i marginesach. Ten sam nagłówek
 * wszędzie jest tańszy niż cztery podobne.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.screen, bottom = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.tiny))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Karta ostrzeżenia/podpowiedzi z opcjonalną akcją. Używana na Home i w Ustawieniach. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (tone) {
        NoticeTone.INFO -> scheme.surfaceContainerHigh
        NoticeTone.WARNING -> scheme.tertiaryContainer
        NoticeTone.ERROR -> scheme.errorContainer
    }
    val accent: Color = when (tone) {
        NoticeTone.INFO -> scheme.primary
        NoticeTone.WARNING -> scheme.tertiary
        NoticeTone.ERROR -> scheme.error
    }
    val onContainer: Color = when (tone) {
        NoticeTone.INFO -> scheme.onSurface
        NoticeTone.WARNING -> scheme.onTertiaryContainer
        NoticeTone.ERROR -> scheme.onErrorContainer
    }

    NightCard(modifier = modifier, container = container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.medium))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
        }
        Spacer(Modifier.height(Spacing.small))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer.copy(alpha = 0.78f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.small))
            TextButton(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Spacing.medium,
                    vertical = Spacing.tiny,
                ),
            ) {
                Text(actionLabel, color = accent)
            }
        }
    }
}

enum class NoticeTone { INFO, WARNING, ERROR }

/** Suwak parametru z wartością i podpisem — podstawowy budulec ekranu Ustawień. */
@Composable
fun ParameterSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    description: String? = null,
) {
    val haptics = rememberHaptics()
    Column(modifier.padding(vertical = Spacing.small)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = {
                // Suwaki są tu skokowe, więc tik odpowiada realnej zmianie wartości, nie ruchowi palca.
                if (it != value) haptics.tick()
                onValueChange(it)
            },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.section, bottom = Spacing.small),
    )
}

/**
 * Pusty ekran mówi, dlaczego jest pusty — inaczej wygląda jak zepsuty.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(Spacing.medium))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Liczba i jej podpis. Wartość dużą czcionką tabelaryczną, podpis wersalikami.
 *
 * Zmiana wartości wjeżdża od dołu zamiast przeskakiwać: liczniki na ekranie głównym tykają w
 * trakcie sesji i skok cyfry czyta się jak usterka, a nie jak nowe zdarzenie.
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically { height -> height / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { height -> -height / 2 } + fadeOut())
            },
            label = "statTile",
        ) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
