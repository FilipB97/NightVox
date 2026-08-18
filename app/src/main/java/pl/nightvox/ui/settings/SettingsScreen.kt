package pl.nightvox.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.ParameterSlider
import pl.nightvox.ui.components.SectionHeader
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing
import pl.nightvox.util.SystemChecks
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenCalibration: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "Ustawienia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 16.dp),
            )

            // --- bramka ---
            SectionHeader("Bramka")
            Text(
                "Wartości domyślne są punktem startowym, nie prawdą objawioną. Po pierwszej nocy " +
                    "zajrzyj do logu diagnostycznego i dostrój je do własnej sypialni.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
                Text("Zmierz tło i dobierz próg (kalibracja)")
            }
            Spacer(Modifier.height(8.dp))

            ParameterSlider(
                label = "Czułość (triggerDeltaDb)",
                valueText = "${settings.triggerDeltaDb.roundToInt()} dB nad tłem",
                value = settings.triggerDeltaDb,
                range = NightVoxSettings.TRIGGER_DELTA_RANGE,
                steps = 17,
                onValueChange = { v -> viewModel.update { it.copy(triggerDeltaDb = v.roundToInt().toFloat()) } },
                description = "Główna gałka. Niżej = więcej klipów i więcej śmieci, wyżej = ryzyko przegapienia cichego mamrotania.",
            )
            ParameterSlider(
                label = "Anty-trzask (attackFrames)",
                valueText = "${settings.attackFrames} ramek · ${settings.attackFrames * 20} ms",
                value = settings.attackFrames.toFloat(),
                range = NightVoxSettings.ATTACK_FRAMES_RANGE.first.toFloat()..NightVoxSettings.ATTACK_FRAMES_RANGE.last.toFloat(),
                steps = 8,
                onValueChange = { v -> viewModel.update { it.copy(attackFrames = v.roundToInt()) } },
                description = "Ile kolejnych ramek musi przekroczyć próg, żeby zacząć nagrywać.",
            )
            ParameterSlider(
                label = "Pre-roll",
                valueText = "${settings.preRollMs} ms",
                value = settings.preRollMs.toFloat(),
                range = NightVoxSettings.PRE_ROLL_RANGE_MS.first.toFloat()..NightVoxSettings.PRE_ROLL_RANGE_MS.last.toFloat(),
                steps = 9,
                onValueChange = { v -> viewModel.update { it.copy(preRollMs = roundTo(v, 500)) } },
                description = "Ile audio sprzed triggera trafia do pliku. Bez tego ginie pierwsza sylaba.",
            )
            ParameterSlider(
                label = "Hangover",
                valueText = "${settings.hangoverMs} ms",
                value = settings.hangoverMs.toFloat(),
                range = NightVoxSettings.HANGOVER_RANGE_MS.first.toFloat()..NightVoxSettings.HANGOVER_RANGE_MS.last.toFloat(),
                steps = 17,
                onValueChange = { v -> viewModel.update { it.copy(hangoverMs = roundTo(v, 500)) } },
                description = "Jak długo nagrywać po spadku poziomu. Pauzy w mowie przez sen bywają długie.",
            )
            ParameterSlider(
                label = "Scalanie (mergeGapMs)",
                valueText = "${settings.mergeGapMs} ms",
                value = settings.mergeGapMs.toFloat(),
                range = NightVoxSettings.MERGE_GAP_RANGE_MS.first.toFloat()..NightVoxSettings.MERGE_GAP_RANGE_MS.last.toFloat(),
                steps = 9,
                onValueChange = { v -> viewModel.update { it.copy(mergeGapMs = roundTo(v, 500)) } },
                description = "Nowy trigger w tym oknie dopisuje się do poprzedniego pliku zamiast tworzyć kolejny klip.",
            )
            ParameterSlider(
                label = "Minimum mowy (minVoicedMs)",
                valueText = "${settings.minVoicedMs} ms",
                value = settings.minVoicedMs.toFloat(),
                range = NightVoxSettings.MIN_VOICED_RANGE_MS.first.toFloat()..NightVoxSettings.MIN_VOICED_RANGE_MS.last.toFloat(),
                steps = 18,
                onValueChange = { v -> viewModel.update { it.copy(minVoicedMs = roundTo(v, 100)) } },
                description = "Klip z mniejszą sumą ramek nad progiem jest kasowany. Filtruje trzaski i skrzypnięcie łóżka.",
            )
            ParameterSlider(
                label = "Maksymalna długość klipu",
                valueText = Format.duration(settings.maxClipMs),
                value = settings.maxClipMs.toFloat(),
                range = NightVoxSettings.MAX_CLIP_RANGE_MS.first.toFloat()..NightVoxSettings.MAX_CLIP_RANGE_MS.last.toFloat(),
                steps = 18,
                onValueChange = { v -> viewModel.update { it.copy(maxClipMs = roundTo(v, 30_000)) } },
                description = "Ochrona przed wentylatorem albo deszczem za oknem, który trzyma bramkę otwartą godzinami.",
            )

            // --- filtr mowy ---
            SectionHeader("Filtr mowy")
            SwitchRow(
                label = "Odsiewaj oddech i chrapanie",
                description = "Bramka reaguje na głośność, więc przepuszcza wszystko, co głośniejsze od tła — " +
                    "także oddech i chrapanie. Analiza widmowa sprawdza, czy w klipie jest struktura mowy: " +
                    "formanty powyżej 300 Hz, zmienność widma i rytm sylab. Nic nie kasuje — klipy pod progiem " +
                    "trafiają do „Odrzucone”.",
                checked = settings.speechFilterEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(speechFilterEnabled = v) } },
            )
            if (settings.speechFilterEnabled) {
                ParameterSlider(
                    label = "Próg mowy",
                    valueText = String.format(java.util.Locale.US, "%.2f", settings.speechFilterThreshold),
                    value = settings.speechFilterThreshold,
                    range = NightVoxSettings.SPEECH_THRESHOLD_RANGE,
                    steps = 12,
                    onValueChange = { v ->
                        viewModel.update { it.copy(speechFilterThreshold = Math.round(v * 20f) / 20f) }
                    },
                    description = "Wyżej = mniej klipów, ale rośnie ryzyko wycięcia cichego mamrotania. " +
                        "Ocena każdego klipu jest widoczna na jego ekranie i w logu diagnostycznym — " +
                        "po nocy warto porównać oceny klipów z mową i tych z samym oddechem.",
                )
            }

            // --- sesja ---
            SectionHeader("Sesja")
            SwitchRow(
                label = "Auto-stop rano",
                checked = settings.autoStopHour != null,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(autoStopHour = if (enabled) 9 else null) }
                },
            )
            if (settings.autoStopHour != null) {
                ParameterSlider(
                    label = "Godzina auto-stopu",
                    valueText = String.format(java.util.Locale.US, "%02d:%02d", settings.autoStopHour, settings.autoStopMinute),
                    value = settings.autoStopHour?.toFloat() ?: 9f,
                    range = 4f..12f,
                    steps = 7,
                    onValueChange = { v -> viewModel.update { it.copy(autoStopHour = v.roundToInt()) } },
                )
            }
            ParameterSlider(
                label = "Maksymalna długość sesji",
                valueText = "${settings.maxSessionHours} h",
                value = settings.maxSessionHours.toFloat(),
                range = 4f..14f,
                steps = 9,
                onValueChange = { v -> viewModel.update { it.copy(maxSessionHours = v.roundToInt()) } },
                description = "Bezpiecznik na wypadek, gdyby auto-stop nie zadziałał.",
            )

            // --- retencja ---
            SectionHeader("Retencja")
            ParameterSlider(
                label = "Trzymaj klipy przez",
                valueText = if (settings.retentionDays <= NightVoxSettings.RETENTION_NEVER) {
                    "bez limitu"
                } else {
                    "${settings.retentionDays} dni"
                },
                value = settings.retentionDays.toFloat(),
                range = 0f..365f,
                onValueChange = { v -> viewModel.setRetentionDays(context, v.roundToInt()) },
                description = "Ulubione nie są kasowane nigdy, niezależnie od tego ustawienia. 0 = nie kasuj nic.",
            )
            SwitchRow(
                label = "Zachowuj odrzucone",
                description = "Zdarzenia poniżej minVoicedMs i klipy pod progiem mowy trafiają do zakładki " +
                    "„Odrzucone” zamiast znikać. " +
                    "Dopóki progi nie są dostrojone, to jedyny sposób sprawdzić, czy filtr nie wycina mowy.",
                checked = settings.keepDiscardedClips,
                onCheckedChange = { v -> viewModel.update { it.copy(keepDiscardedClips = v) } },
            )
            if (settings.keepDiscardedClips) {
                ParameterSlider(
                    label = "Trzymaj odrzucone przez",
                    valueText = "${settings.discardedRetentionDays} dni",
                    value = settings.discardedRetentionDays.toFloat(),
                    range = NightVoxSettings.DISCARDED_RETENTION_RANGE_DAYS.first.toFloat()..
                        NightVoxSettings.DISCARDED_RETENTION_RANGE_DAYS.last.toFloat(),
                    steps = 28,
                    onValueChange = { v -> viewModel.update { it.copy(discardedRetentionDays = v.roundToInt()) } },
                    description = "Kosz służy do strojenia, więc ma krótszy termin niż zwykłe nagrania.",
                )
            }
            OutlinedButton(onClick = viewModel::runRetentionNow, modifier = Modifier.fillMaxWidth()) {
                Text("Zastosuj retencję teraz")
            }

            // --- miejsce ---
            SectionHeader("Miejsce na dysku")
            InfoRow("Klipy", "${storage.clipCount} plików · ${Format.bytes(storage.clipBytes)}")
            InfoRow("Log diagnostyczny", Format.bytes(storage.diagnosticsBytes))
            InfoRow("Dumpy WAV", Format.bytes(storage.debugDumpBytes))
            InfoRow("Pliki bez wpisu w bazie", storage.orphanedFiles.toString())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::deleteOrphanedFiles, modifier = Modifier.weight(1f)) {
                    Text("Posprzątaj sieroty")
                }
                OutlinedButton(onClick = viewModel::deleteDebugDumps, modifier = Modifier.weight(1f)) {
                    Text("Skasuj WAV-y")
                }
            }

            // --- diagnostyka ---
            SectionHeader("Diagnostyka")
            SwitchRow(
                label = "Zapisuj log diagnostyczny",
                description = "Przejścia bramki, poziom, tło, przerwania. Bez tego strojenie progów to zgadywanka.",
                checked = settings.diagnosticsEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(diagnosticsEnabled = v) } },
            )
            SwitchRow(
                label = "Dump całej sesji do WAV",
                description = "Nagrywa też wszystko, co bramka odrzuciła — ok. 115 MB na godzinę. Tylko do strojenia.",
                checked = settings.debugWavDump,
                onCheckedChange = { v -> viewModel.update { it.copy(debugWavDump = v) } },
            )
            SwitchRow(
                label = "Nie gaś ekranu",
                description = "Do podglądania metera. Sesja działa też przy zablokowanym ekranie.",
                checked = settings.keepScreenOn,
                onCheckedChange = { v -> viewModel.update { it.copy(keepScreenOn = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val file = viewModel.diagnosticsFile()
                        if (file != null) {
                            Sharing.shareFile(context, file, "text/plain", "Log diagnostyczny NightVox")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Udostępnij log") }
                OutlinedButton(onClick = viewModel::clearDiagnostics, modifier = Modifier.weight(1f)) {
                    Text("Wyczyść log")
                }
            }

            // --- rozwiązywanie problemów ---
            SectionHeader("Rozwiązywanie problemów")
            NoticeCard(
                icon = Icons.Filled.BatteryAlert,
                title = "Sesja urywa się w środku nocy",
                text = "Xiaomi, Samsung, OnePlus, Huawei i Oppo agresywnie ubijają procesy działające w tle — " +
                    "niezależnie od tego, że NightVox jest usługą pierwszoplanową. Zwolnij apkę z optymalizacji " +
                    "baterii, a w ustawieniach producenta włącz „autostart” i wyłącz „oszczędzanie energii” dla NightVoxa. " +
                    "Konkretne kroki dla twojego modelu opisuje dontkillmyapp.com.",
                tone = NoticeTone.INFO,
                actionLabel = "Otwórz dontkillmyapp.com",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SystemChecks.dontKillMyAppUrl)),
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(SystemChecks.batteryOptimizationIntent(context)) }
                            .onFailure { context.startActivity(SystemChecks.batterySettingsIntent()) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Bateria") }
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(SystemChecks.appDetailsIntent(context)) } },
                    modifier = Modifier.weight(1f),
                ) { Text("Uprawnienia") }
            }

            SectionHeader("O aplikacji")
            NoticeCard(
                icon = Icons.Filled.BugReport,
                title = "Wszystko zostaje na telefonie",
                text = "NightVox nie ma uprawnienia INTERNET — nagrania fizycznie nie mogą opuścić urządzenia " +
                    "inaczej niż przez świadome udostępnienie pliku. Jeśli w pokoju śpi ktoś jeszcze, powiedz mu, " +
                    "że nagrywanie działa: nagrywanie siebie jest legalne, cudzych wypowiedzi bez wiedzy już niekoniecznie.",
                tone = NoticeTone.INFO,
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::resetToDefaults, modifier = Modifier.fillMaxWidth()) {
                Text("Przywróć domyślne")
            }
            Spacer(Modifier.height(32.dp))
        }
        SnackbarHost(snackbarHostState)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun roundTo(value: Float, step: Int): Long =
    ((value / step).roundToInt().toLong() * step)
