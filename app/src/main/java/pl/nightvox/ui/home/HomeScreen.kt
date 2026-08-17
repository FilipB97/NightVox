package pl.nightvox.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.nightvox.service.RecorderService
import pl.nightvox.ui.components.LiveLevelMeter
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.StatTile
import pl.nightvox.util.Format
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

    LaunchedEffect(Unit) { viewModel.refreshEnvironment() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = if (state.isRunning) "Sesja trwa" else "NightVox",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = if (state.isRunning) {
                Format.duration(nowMs - state.startedAtMs)
            } else {
                "Nagrywa tylko zdarzenia dźwiękowe, nie całą noc"
            },
            style = if (state.isRunning) MaterialTheme.typography.displayLarge else MaterialTheme.typography.bodyMedium,
            color = if (state.isRunning) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

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

        Spacer(Modifier.height(24.dp))

        if (state.isRunning) {
            RunningSessionPanel(state = state, history = history, nowMs = nowMs)
            Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Kalibracja")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Parametry")
            }
        }

        Spacer(Modifier.height(24.dp))
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
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = if (isRunning) onStop else onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        shape = CircleShape,
        colors = colors,
    ) {
        if (isRunning) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text(
                if (isRecordingClip) "Nagrywa — zatrzymaj" else "Zatrzymaj",
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text("Zacznij nasłuchiwać", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RunningSessionPanel(
    state: pl.nightvox.service.RecorderState,
    history: List<Float>,
    nowMs: Long,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = when {
                    state.isSilenced -> "Mikrofon wyciszony przez system"
                    state.isWarmingUp -> "Pomiar tła — ${(state.warmupRemainingMs / 1000)} s"
                    state.isRecordingClip -> "Nagrywa klip"
                    else -> "Nasłuchuje"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (state.isRecordingClip) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            LiveLevelMeter(
                history = history,
                levelDb = state.levelDb,
                floorDb = state.floorDb,
                thresholdDb = state.thresholdDb,
                isRecording = state.isRecordingClip,
                capacity = HomeViewModel.HISTORY_CAPACITY,
            )

            Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(12.dp))
                Text(
                    "Źródło: ${state.audioSource}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
