package pl.nightvox.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import pl.nightvox.ui.components.rememberHaptics
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.SystemChecks

/**
 * Pierwsze uruchomienie w trzech krokach.
 *
 * Wcześniej nowy użytkownik dostawał ekran główny ze stosem kart-ostrzeżeń i musiał sam
 * zgadnąć, w jakiej kolejności je odhaczyć. To bolało podwójnie, odkąd próg ma bezwzględną
 * podłogę: bez kalibracji aplikacja albo nie nagra nic, albo nagra wszystko, a jedno i drugie
 * wygląda jak awaria. Kroki są ustawione w kolejności, w której naprawdę trzeba je zrobić.
 */
@Composable
fun OnboardingScreen(
    onOpenCalibration: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // rememberSaveable, nie remember: z kroku 2 wychodzi się do kalibracji, a po powrocie
    // powitanie musi wznowić się tam, gdzie było, a nie zacząć od nowa.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var hasPermissions by rememberSaveable { mutableStateOf(context.hasRecordingPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = context.hasRecordingPermissions()
        if (hasPermissions) {
            haptics.confirm()
            step = 1
        } else {
            haptics.reject()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Spacing.section),
    ) {
        Spacer(Modifier.height(Spacing.section))
        StepDots(current = step, total = STEP_COUNT)

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val shift = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { width -> shift * width / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { width -> -shift * width / 6 } + fadeOut(tween(140)))
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                0 -> StepBody(
                    icon = Icons.Filled.Mic,
                    eyebrow = "KROK 1 Z 3",
                    title = "Nagrywa zdarzenia, nie całą noc",
                    body = "NightVox słucha przez sen i zapisuje tylko to, co przekroczy próg — kilka " +
                        "albo kilkadziesiąt kilkunastosekundowych klipów zamiast ośmiu godzin ciszy.\n\n" +
                        "Wszystko zostaje na telefonie. Aplikacja nie ma uprawnienia do internetu, " +
                        "więc nagrania fizycznie nie mogą go opuścić inaczej niż przez świadome " +
                        "udostępnienie pliku.",
                )

                1 -> StepBody(
                    icon = Icons.Filled.Tune,
                    eyebrow = "KROK 2 Z 3",
                    title = "Zmierz swój głos",
                    body = "To jest krok, którego nie warto pomijać. Próg wyzwolenia ma bezwzględną " +
                        "podłogę, a jej domyślna wartość jest zgadnięta — nie zmierzona u Ciebie.\n\n" +
                        "Kalibracja trwa dwadzieścia sekund: cisza w sypialni i jedna cicha wypowiedź. " +
                        "Bez niej aplikacja albo nie nagra nic, albo nagra każde skrzypnięcie łóżka.",
                )

                else -> StepBody(
                    icon = Icons.Filled.BatteryChargingFull,
                    eyebrow = "KROK 3 Z 3",
                    title = "Zanim zaśniesz",
                    body = "Podłącz ładowarkę — nasłuch przez całą noc kosztuje baterię. Zwolnij " +
                        "NightVoxa z optymalizacji baterii, bo część telefonów ubija sesję po kilku " +
                        "godzinach.\n\nPotem zostaje jedno: nacisnąć „Zacznij nasłuchiwać” i zgasić ekran.",
                )
            }
        }

        when (step) {
            0 -> {
                Button(
                    onClick = {
                        if (hasPermissions) {
                            haptics.tick()
                            step = 1
                        } else {
                            permissionLauncher.launch(context.requiredPermissions())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Text(if (hasPermissions) "Dalej" else "Przyznaj mikrofon i powiadomienia")
                }
                if (!hasPermissions) {
                    Spacer(Modifier.height(Spacing.small))
                    Text(
                        "Bez mikrofonu aplikacja nie zrobi nic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            1 -> {
                Button(
                    onClick = { haptics.confirm(); onOpenCalibration() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Text("Kalibruj teraz (20 s)")
                }
                Spacer(Modifier.height(Spacing.tiny))
                TextButton(
                    onClick = { haptics.tick(); step = 2 },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dalej")
                }
            }

            else -> {
                Button(
                    onClick = {
                        haptics.tick()
                        runCatching { context.startActivity(SystemChecks.batteryOptimizationIntent(context)) }
                            .onFailure { context.startActivity(SystemChecks.batterySettingsIntent()) }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Text("Zwolnij z optymalizacji baterii")
                }
                Spacer(Modifier.height(Spacing.tiny))
                TextButton(
                    onClick = { haptics.confirm(); onFinish() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.small))
                        Text("Gotowe, przejdź do aplikacji")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBody(icon: ImageVector, eyebrow: String, title: String, body: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(Spacing.section))
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.small))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.large))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        repeat(total) { index ->
            val active = index <= current
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private const val STEP_COUNT = 3

private fun Context.requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun Context.hasRecordingPermissions(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
