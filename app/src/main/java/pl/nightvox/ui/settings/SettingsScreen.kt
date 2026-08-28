package pl.nightvox.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.ParameterSlider
import pl.nightvox.ui.components.ScreenHeader
import pl.nightvox.ui.components.rememberHaptics
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format
import pl.nightvox.util.Sharing
import pl.nightvox.util.SystemChecks

/**
 * Ustawienia jako harmonijka, nie jako zwój.
 *
 * Ekran urósł do trzynastu suwaków, sześciu przełączników i dziesięciu przycisków ułożonych
 * płasko jeden pod drugim, każdy z akapitem wyjaśnienia. Wszystko było równie ważne i
 * wszystko widoczne naraz, więc nie dawało się tego przejrzeć wzrokiem — a przecież po
 * pierwszym ustawieniu wraca się tu po jedną rzecz.
 *
 * Teraz sekcje są zwinięte, otwarta jest najwyżej jedna, a zwinięty nagłówek pokazuje
 * bieżące wartości — dzięki temu odpowiada na „jak to jest ustawione" bez otwierania.
 * Osiem parametrów, których po kalibracji nikt nie rusza, siedzi w „Zaawansowanych".
 */
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
    val haptics = rememberHaptics()

    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val section: (String) -> SectionControl = { name ->
        SectionControl(
            expanded = openSection == name,
            onToggle = {
                haptics.tick()
                openSection = if (openSection == name) null else name
            },
        )
    }

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
                .padding(horizontal = Spacing.screen),
        ) {
            ScreenHeader(title = "Ustawienia", subtitle = "Progi, filtr i porządki")

            CalibrationCard(onOpenCalibration = { haptics.confirm(); onOpenCalibration() })
            Spacer(Modifier.height(Spacing.large))

            SettingsSection(
                title = "Czułość",
                summary = "${settings.triggerDeltaDb.roundToInt()} dB nad tłem · podłoga ${Format.db(settings.minTriggerDb)}",
                control = section("czulosc"),
            ) {
                ParameterSlider(
                    label = "Czułość (triggerDeltaDb)",
                    valueText = "${settings.triggerDeltaDb.roundToInt()} dB nad tłem",
                    value = settings.triggerDeltaDb,
                    range = NightVoxSettings.TRIGGER_DELTA_RANGE,
                    steps = 17,
                    onValueChange = { v -> viewModel.update { it.copy(triggerDeltaDb = v.roundToInt().toFloat()) } },
                    description = "Główna gałka. Niżej = więcej klipów i więcej śmieci, wyżej = ryzyko " +
                        "przegapienia cichego mamrotania.",
                )
                ParameterSlider(
                    label = "Podłoga progu (minTriggerDb)",
                    valueText = Format.db(settings.minTriggerDb),
                    value = settings.minTriggerDb,
                    range = NightVoxSettings.MIN_TRIGGER_RANGE_DB,
                    steps = 39,
                    onValueChange = { v -> viewModel.update { it.copy(minTriggerDb = v.roundToInt().toFloat()) } },
                    description = "Próg nie zejdzie poniżej tej wartości, choćby pokój był bardzo cichy. " +
                        "W cichej sypialni tło potrafi spaść do −78 dB i wtedy „tło + czułość” wypada na " +
                        "poziomie, na którym nie ma już nic słyszalnego. Kalibracja wylicza to z Twojego głosu.",
                )
            }

            SettingsSection(
                title = "Filtr mowy",
                summary = if (settings.speechFilterEnabled) {
                    "włączony · próg ${Format.score(settings.speechFilterThreshold)}"
                } else {
                    "wyłączony"
                },
                control = section("filtr"),
            ) {
                SwitchRow(
                    label = "Odsiewaj oddech i chrapanie",
                    description = "Bramka reaguje na głośność, więc przepuszcza wszystko głośniejsze od tła — " +
                        "także oddech i chrapanie. Analiza widmowa sprawdza, czy w klipie jest struktura mowy. " +
                        "Nic nie kasuje — klipy pod progiem trafiają do „Odrzucone”.",
                    checked = settings.speechFilterEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(speechFilterEnabled = v) } },
                )
                if (settings.speechFilterEnabled) {
                    ParameterSlider(
                        label = "Próg mowy",
                        valueText = Format.score(settings.speechFilterThreshold),
                        value = settings.speechFilterThreshold,
                        range = NightVoxSettings.SPEECH_THRESHOLD_RANGE,
                        steps = 12,
                        onValueChange = { v ->
                            viewModel.update { it.copy(speechFilterThreshold = Math.round(v * 20f) / 20f) }
                        },
                        description = "Wyżej = mniej klipów, ale rośnie ryzyko wycięcia cichego mamrotania. " +
                            "Ocena każdego klipu jest widoczna na jego ekranie i w logu — po nocy warto " +
                            "porównać oceny klipów z mową i tych z samym oddechem.",
                    )
                }
            }

            SettingsSection(
                title = "Sesja",
                summary = buildString {
                    append(
                        settings.autoStopHour?.let {
                            "auto-stop ${String.format(Locale.US, "%02d:%02d", it, settings.autoStopMinute)}"
                        } ?: "bez auto-stopu",
                    )
                    append(" · max ${settings.maxSessionHours} h")
                },
                control = section("sesja"),
            ) {
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
                        valueText = String.format(
                            Locale.US,
                            "%02d:%02d",
                            settings.autoStopHour,
                            settings.autoStopMinute,
                        ),
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
            }

            SettingsSection(
                title = "Retencja",
                summary = buildString {
                    append(
                        if (settings.retentionDays <= NightVoxSettings.RETENTION_NEVER) {
                            "klipy bez limitu"
                        } else {
                            "klipy ${settings.retentionDays} dni"
                        },
                    )
                    if (settings.keepDiscardedClips) append(" · kosz ${settings.discardedRetentionDays} dni")
                },
                control = section("retencja"),
            ) {
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
                        "„Odrzucone” zamiast znikać. Dopóki progi nie są dostrojone, to jedyny sposób sprawdzić, " +
                        "czy filtr nie wycina mowy.",
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
                Spacer(Modifier.height(Spacing.small))
                OutlinedButton(onClick = viewModel::runRetentionNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Zastosuj retencję teraz")
                }
            }

            SettingsSection(
                title = "Zaawansowane",
                summary = "pre-roll ${settings.preRollMs / 1000} s · hangover ${settings.hangoverMs / 1000} s · " +
                    "min. mowy ${settings.minVoicedMs} ms",
                control = section("zaawansowane"),
            ) {
                Text(
                    "Kształt klipu, nie czułość. Po kalibracji rzadko trzeba tu wracać — a jeśli już, to " +
                        "z logiem diagnostycznym z konkretnej nocy pod ręką.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.small))
                ParameterSlider(
                    label = "Anty-trzask (attackFrames)",
                    valueText = "${settings.attackFrames} ramek · ${settings.attackFrames * 20} ms",
                    value = settings.attackFrames.toFloat(),
                    range = NightVoxSettings.ATTACK_FRAMES_RANGE.first.toFloat()..
                        NightVoxSettings.ATTACK_FRAMES_RANGE.last.toFloat(),
                    steps = 8,
                    onValueChange = { v -> viewModel.update { it.copy(attackFrames = v.roundToInt()) } },
                    description = "Ile kolejnych ramek musi przekroczyć próg, żeby zacząć nagrywać.",
                )
                ParameterSlider(
                    label = "Pre-roll",
                    valueText = "${settings.preRollMs} ms",
                    value = settings.preRollMs.toFloat(),
                    range = NightVoxSettings.PRE_ROLL_RANGE_MS.first.toFloat()..
                        NightVoxSettings.PRE_ROLL_RANGE_MS.last.toFloat(),
                    steps = 9,
                    onValueChange = { v -> viewModel.update { it.copy(preRollMs = roundTo(v, 500)) } },
                    description = "Ile audio sprzed triggera trafia do pliku. Bez tego ginie pierwsza sylaba.",
                )
                ParameterSlider(
                    label = "Hangover",
                    valueText = "${settings.hangoverMs} ms",
                    value = settings.hangoverMs.toFloat(),
                    range = NightVoxSettings.HANGOVER_RANGE_MS.first.toFloat()..
                        NightVoxSettings.HANGOVER_RANGE_MS.last.toFloat(),
                    steps = 17,
                    onValueChange = { v -> viewModel.update { it.copy(hangoverMs = roundTo(v, 500)) } },
                    description = "Jak długo nagrywać po spadku poziomu. Pauzy w mowie przez sen bywają długie.",
                )
                ParameterSlider(
                    label = "Scalanie (mergeGapMs)",
                    valueText = "${settings.mergeGapMs} ms",
                    value = settings.mergeGapMs.toFloat(),
                    range = NightVoxSettings.MERGE_GAP_RANGE_MS.first.toFloat()..
                        NightVoxSettings.MERGE_GAP_RANGE_MS.last.toFloat(),
                    steps = 9,
                    onValueChange = { v -> viewModel.update { it.copy(mergeGapMs = roundTo(v, 500)) } },
                    description = "Nowy trigger w tym oknie dopisuje się do poprzedniego pliku zamiast tworzyć " +
                        "kolejny klip.",
                )
                ParameterSlider(
                    label = "Minimum mowy (minVoicedMs)",
                    valueText = "${settings.minVoicedMs} ms",
                    value = settings.minVoicedMs.toFloat(),
                    range = NightVoxSettings.MIN_VOICED_RANGE_MS.first.toFloat()..
                        NightVoxSettings.MIN_VOICED_RANGE_MS.last.toFloat(),
                    steps = 18,
                    onValueChange = { v -> viewModel.update { it.copy(minVoicedMs = roundTo(v, 100)) } },
                    description = "Klip z mniejszą sumą ramek nad progiem trafia do kosza. Filtruje trzaski i " +
                        "skrzypnięcie łóżka.",
                )
                ParameterSlider(
                    label = "Maksymalna długość klipu",
                    valueText = Format.duration(settings.maxClipMs),
                    value = settings.maxClipMs.toFloat(),
                    range = NightVoxSettings.MAX_CLIP_RANGE_MS.first.toFloat()..
                        NightVoxSettings.MAX_CLIP_RANGE_MS.last.toFloat(),
                    steps = 18,
                    onValueChange = { v -> viewModel.update { it.copy(maxClipMs = roundTo(v, 30_000)) } },
                    description = "Ochrona przed wentylatorem albo deszczem za oknem, który trzyma bramkę " +
                        "otwartą godzinami.",
                )
            }

            SettingsSection(
                title = "Miejsce na dysku",
                summary = "${storage.clipCount} plików · ${Format.bytes(storage.clipBytes)}",
                control = section("miejsce"),
            ) {
                InfoRow("Klipy", "${storage.clipCount} plików · ${Format.bytes(storage.clipBytes)}")
                InfoRow("Log diagnostyczny", Format.bytes(storage.diagnosticsBytes))
                InfoRow("Dumpy WAV", Format.bytes(storage.debugDumpBytes))
                InfoRow("Pliki bez wpisu w bazie", storage.orphanedFiles.toString())
                Spacer(Modifier.height(Spacing.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    OutlinedButton(onClick = viewModel::deleteOrphanedFiles, modifier = Modifier.weight(1f)) {
                        Text("Posprzątaj sieroty")
                    }
                    OutlinedButton(onClick = viewModel::deleteDebugDumps, modifier = Modifier.weight(1f)) {
                        Text("Skasuj WAV-y")
                    }
                }
            }

            SettingsSection(
                title = "Diagnostyka",
                summary = buildString {
                    append(if (settings.diagnosticsEnabled) "log włączony" else "log wyłączony")
                    if (settings.debugWavDump) append(" · dump WAV")
                    if (settings.keepScreenOn) append(" · ekran nie gaśnie")
                },
                control = section("diagnostyka"),
            ) {
                SwitchRow(
                    label = "Zapisuj log diagnostyczny",
                    description = "Przejścia bramki, poziom, tło, przerwania. Bez tego strojenie progów to " +
                        "zgadywanka.",
                    checked = settings.diagnosticsEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(diagnosticsEnabled = v) } },
                )
                SwitchRow(
                    label = "Dump całej sesji do WAV",
                    description = "Nagrywa też wszystko, co bramka odrzuciła — ok. 115 MB na godzinę. Tylko do " +
                        "strojenia.",
                    checked = settings.debugWavDump,
                    onCheckedChange = { v -> viewModel.update { it.copy(debugWavDump = v) } },
                )
                SwitchRow(
                    label = "Nie gaś ekranu",
                    description = "Do podglądania metera. Sesja działa też przy zablokowanym ekranie.",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { v -> viewModel.update { it.copy(keepScreenOn = v) } },
                )
                Spacer(Modifier.height(Spacing.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
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
            }

            SettingsSection(
                title = "Rozwiązywanie problemów",
                summary = "sesja urywa się w nocy, uprawnienia, bateria",
                control = section("problemy"),
            ) {
                NoticeCard(
                    icon = Icons.Filled.BatteryAlert,
                    title = "Sesja urywa się w środku nocy",
                    text = "Xiaomi, Samsung, OnePlus, Huawei i Oppo agresywnie ubijają procesy działające w " +
                        "tle — niezależnie od tego, że NightVox jest usługą pierwszoplanową. Zwolnij apkę z " +
                        "optymalizacji baterii, a w ustawieniach producenta włącz „autostart” i wyłącz " +
                        "„oszczędzanie energii”. Kroki dla twojego modelu opisuje dontkillmyapp.com.",
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
                Spacer(Modifier.height(Spacing.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
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
            }

            SettingsSection(
                title = "O aplikacji",
                summary = "prywatność, licencje",
                control = section("o-aplikacji"),
            ) {
                NoticeCard(
                    icon = Icons.Filled.BugReport,
                    title = "Wszystko zostaje na telefonie",
                    text = "NightVox nie ma uprawnienia INTERNET — nagrania fizycznie nie mogą opuścić " +
                        "urządzenia inaczej niż przez świadome udostępnienie pliku. Jeśli w pokoju śpi ktoś " +
                        "jeszcze, powiedz mu, że nagrywanie działa: nagrywanie siebie jest legalne, cudzych " +
                        "wypowiedzi bez wiedzy już niekoniecznie.",
                    tone = NoticeTone.INFO,
                )
                Spacer(Modifier.height(Spacing.medium))
                Text(
                    "Krój pisma: Inter (SIL Open Font License 1.1), przycięty do znaków używanych w " +
                        "aplikacji. Pełna treść licencji leży w pliku assets/inter_OFL.txt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.section))
            TextButton(
                onClick = { haptics.reject(); viewModel.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Przywróć domyślne", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Spacing.section))
        }
        SnackbarHost(snackbarHostState)
    }
}

/** Stan jednej sekcji harmonijki. Otwarta jest najwyżej jedna — inaczej wraca ściana treści. */
internal data class SectionControl(val expanded: Boolean, val onToggle: () -> Unit)

/**
 * Zwinięta sekcja pokazuje bieżące wartości, więc odpowiada na „jak to jest ustawione”
 * bez otwierania. Po otwarciu podsumowanie znika — te same liczby są już przy suwakach.
 */
@Composable
internal fun SettingsSection(
    title: String,
    summary: String,
    control: SectionControl,
    content: @Composable ColumnScope.() -> Unit,
) {
    val angle by animateFloatAsState(if (control.expanded) 180f else 0f, label = "chevron")

    NightCard(
        modifier = Modifier.padding(bottom = Spacing.small),
        container = if (control.expanded) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        padding = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = control.onToggle)
                .padding(Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!control.expanded && summary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (control.expanded) "Zwiń" else "Rozwiń",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(angle),
            )
        }
        AnimatedVisibility(
            visible = control.expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier.padding(
                    start = Spacing.large,
                    end = Spacing.large,
                    bottom = Spacing.large,
                ),
                content = content,
            )
        }
    }
}

/**
 * Kalibracja stoi nad harmonijką, bo to jest właściwa odpowiedź na „jak ustawić próg”.
 * Suwaki poniżej są dla tych, którzy wiedzą, co robią — nie dla kogoś, kto tu trafił pierwszy raz.
 */
@Composable
private fun CalibrationCard(onOpenCalibration: () -> Unit) {
    NightCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Kalibracja",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = Spacing.medium),
            )
        }
        Spacer(Modifier.height(Spacing.small))
        Text(
            "Dwadzieścia sekund: cisza w sypialni i jedna cicha wypowiedź. Ustawia próg i jego " +
                "podłogę z Twojego głosu zamiast z moich domyślnych wartości.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(Spacing.medium))
        OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
            Text("Zmierz tło i dobierz próg", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.large)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { haptics.tick(); onCheckedChange(it) },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.tiny),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun roundTo(value: Float, step: Int): Long =
    ((value / step).roundToInt().toLong() * step)
