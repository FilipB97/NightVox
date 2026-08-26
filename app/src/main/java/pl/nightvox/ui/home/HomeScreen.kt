package pl.nightvox.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.nightvox.service.RecorderService
import pl.nightvox.ui.components.LiveLevelMeter
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.StatTile
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing
import pl.nightvox.util.SystemChecks

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val history by viewModel.levelHistory.collectAsStateWithLifecycle()
    val lastCrash by viewModel.lastCrash.collectAsStateWithLifecycle()

    var hasMicPermission by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var hasNotificationPermission by remember { mutableStateOf(context.hasNotificationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasMicPermission = result[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
        hasNotificationPermission = context.hasNotificationPermission()
        // Serwis startuje dopiero po przyznaniu mikrofonu — i tylko stąd, z widocznego
        // Activity, bo od API 34 FGS mikrofonowy nie wstanie z tła.
        if (hasMicPermission) RecorderService.start(context)
    }

    // Licznik czasu trwania sesji — tyka niezależnie od strumienia poziomu.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRunning) {
        while (state.isRunning) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshEnvironment()
        viewModel.refreshCrashReport()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.section))
        Text(
            text = if (state.isRunning) "SESJA TRWA" else "NIGHTVOX",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.isRunning) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(Spacing.small))
        if (state.isRunning) {
            Text(
                text = Format.duration(nowMs - state.startedAtMs),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            Text(
                text = "Nasłuch wyłączony",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(Spacing.tiny))
            Text(
                text = "Nagrywa tylko zdarzenia dźwiękowe, nie całą noc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.section))

        StartStopButton(
            isRunning = state.isRunning,
            isRecordingClip = state.isRecordingClip,
            onStart = {
                val missing = buildList {
                    if (!hasMicPermission) add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (missing.isEmpty()) RecorderService.start(context) else permissionLauncher.launch(missing.toTypedArray())
            },
            onStop = { RecorderService.stop(context) },
        )

        Spacer(Modifier.height(Spacing.screen))

        lastCrash?.let { summary ->
            NoticeCard(
                icon = Icons.Filled.ErrorOutline,
                title = "Poprzednim razem aplikacja się wywaliła",
                text = "$summary\n\nLog zawiera pełny ślad błędu razem z modelem telefonu i wersją " +
                    "Androida. Udostępnij go — bez tego nie da się tego naprawić.",
                tone = NoticeTone.ERROR,
                actionLabel = "Udostępnij log błędu",
                onAction = {
                    viewModel.crashLogFile()?.let { file ->
                        Sharing.shareFile(context, file, "text/plain", "Log błędu NightVox")
                    }
                    viewModel.dismissCrash()
                },
            )
            Spacer(Modifier.height(Spacing.large))
        }

        if (state.isRunning) {
            RunningSessionPanel(state = state, history = history, nowMs = nowMs)
            Spacer(Modifier.height(Spacing.large))
        }

        Warnings(
            context = context,
            hasMicPermission = hasMicPermission,
            hasNotificationPermission = hasNotificationPermission,
            state = state,
            environment = environment,
            onRequestPermissions = {
                permissionLauncher.launch(
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray(),
                )
            },
        )

        Spacer(Modifier.height(Spacing.large))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            OutlinedButton(
                onClick = onOpenCalibration,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.small))
                Text("Kalibracja")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text("Parametry")
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }
}

@Composable
private fun StartStopButton(
    isRunning: Boolean,
    isRecordingClip: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = if (isRunning) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.error,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = if (isRunning) onStop else onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = CircleShape,
        colors = colors,
    ) {
        Icon(
            if (isRunning) Icons.Filled.Stop else Icons.Filled.Bolt,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(Spacing.medium))
        Text(
            when {
                isRunning && isRecordingClip -> "Nagrywa — zatrzymaj"
                isRunning -> "Zatrzymaj"
                else -> "Zacznij nasłuchiwać"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun RunningSessionPanel(
    state: pl.nightvox.service.RecorderState,
    history: List<Float>,
    nowMs: Long,
) {
    val label = when {
        state.isSilenced -> "Mikrofon wyciszony przez system"
        state.isWarmingUp -> "Pomiar tła — ${state.warmupRemainingMs / 1000} s"
        state.isRecordingClip -> "Nagrywa klip"
        else -> "Nasłuchuje"
    }
    val accent = when {
        state.isSilenced -> MaterialTheme.colorScheme.error
        state.isRecordingClip -> MaterialTheme.colorScheme.error
        state.isWarmingUp -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    NightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = accent, animate = state.isRecordingClip)
            Spacer(Modifier.width(Spacing.small))
            Text(label, style = MaterialTheme.typography.titleSmall, color = accent)
        }
        Spacer(Modifier.height(Spacing.large))

        LiveLevelMeter(
            history = history,
            levelDb = state.levelDb,
            floorDb = state.floorDb,
            thresholdDb = state.thresholdDb,
            isRecording = state.isRecordingClip,
            capacity = HomeViewModel.HISTORY_CAPACITY,
        )

        Spacer(Modifier.height(Spacing.large))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatTile("klipy", state.clipCount.toString())
            StatTile("odrzucone", state.discardedCount.toString())
            StatTile("przerwania", state.interruptions.toString())
            StatTile("czas", Format.duration(nowMs - state.startedAtMs))
        }
        if (state.audioSource.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.medium))
            Text(
                "Źródło: ${state.audioSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kropka statusu. Pulsuje tylko wtedy, gdy naprawdę leci zapis — inaczej to ozdóbka. */
@Composable
private fun PulsingDot(color: Color, animate: Boolean) {
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun Warnings(
    context: Context,
    hasMicPermission: Boolean,
    hasNotificationPermission: Boolean,
    state: pl.nightvox.service.RecorderState,
    environment: pl.nightvox.util.EnvironmentStatus,
    onRequestPermissions: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        if (!hasMicPermission) {
            NoticeCard(
                icon = Icons.Filled.MicOff,
                title = "Brak dostępu do mikrofonu",
                text = "Bez tego uprawnienia apka nie zrobi nic. Nagrania zostają na telefonie — apka nie ma nawet uprawnienia do internetu.",
                tone = NoticeTone.ERROR,
                actionLabel = "Przyznaj",
                onAction = onRequestPermissions,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            NoticeCard(
                icon = Icons.Filled.NotificationsOff,
                title = "Notyfikacje wyłączone",
                text = "Serwis będzie działał, ale nie zobaczysz jego statusu ani ostrzeżeń o przerwaniu nagrywania.",
                tone = NoticeTone.WARNING,
                actionLabel = "Przyznaj",
                onAction = onRequestPermissions,
            )
        }
        if (state.isSilenced) {
            NoticeCard(
                icon = Icons.Filled.MicOff,
                title = "System wyciszył mikrofon",
                text = "Inna aplikacja albo połączenie przejęło mikrofon. NightVox dostaje same zera i próbuje wznowić nagrywanie.",
                tone = NoticeTone.ERROR,
            )
        }
        state.lastError?.let { error ->
            NoticeCard(
                icon = Icons.Filled.ErrorOutline,
                title = "Ostatni problem",
                text = error,
                tone = NoticeTone.WARNING,
            )
        }
        if (!environment.ignoringBatteryOptimizations) {
            NoticeCard(
                icon = Icons.Filled.BatteryAlert,
                title = "Optymalizacja baterii aktywna",
                text = "System może uśpić NightVoxa w środku nocy. Zwolnienie z optymalizacji jest opcjonalne, ale bez niego część telefonów ubija sesję po kilku godzinach.",
                tone = NoticeTone.WARNING,
                actionLabel = "Zwolnij z optymalizacji",
                onAction = {
                    runCatching { context.startActivity(SystemChecks.batteryOptimizationIntent(context)) }
                        .onFailure { context.startActivity(SystemChecks.batterySettingsIntent()) }
                },
            )
        }
        if (!environment.isCharging) {
            NoticeCard(
                icon = Icons.Filled.BatteryAlert,
                title = "Telefon nie jest na ładowarce",
                text = "Nasłuch całą noc zjada baterię. Podłącz ładowarkę przed snem.",
                tone = NoticeTone.INFO,
            )
        }
        if (environment.lowStorage) {
            NoticeCard(
                icon = Icons.Filled.Storage,
                title = "Mało miejsca (${Format.bytes(environment.freeBytes)})",
                text = "Poniżej 200 MB sesja zostanie automatycznie zatrzymana.",
                tone = NoticeTone.WARNING,
            )
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasNotificationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
