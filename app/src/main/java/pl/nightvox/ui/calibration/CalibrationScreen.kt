package pl.nightvox.ui.calibration

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.nightvox.data.NightVoxSettings
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.NoticeCard
import pl.nightvox.ui.components.NoticeTone
import pl.nightvox.ui.components.ParameterSlider
import pl.nightvox.ui.components.SectionHeader
import pl.nightvox.ui.components.StatTile
import pl.nightvox.ui.components.rememberHaptics
import pl.nightvox.ui.theme.Spacing
import pl.nightvox.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kalibracja") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
        ) {
            Text(
                "Zmierz realne tło w sypialni zamiast zgadywać próg. Zrób to w warunkach nocnych: " +
                    "zgaszone światło, zamknięte okno, telefon tam, gdzie będzie leżał w nocy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let {
                Spacer(Modifier.height(16.dp))
                NoticeCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = "Problem z pomiarem",
                    text = it,
                    tone = NoticeTone.ERROR,
                )
            }

            SectionHeader("Krok 1 — 15 s ciszy")

            when (state.phase) {
                CalibrationPhase.MEASURING_FLOOR -> MeasuringCard(
                    caption = "Nie ruszaj się i nic nie mów",
                    remainingMs = state.remainingMs,
                    totalMs = CalibrationViewModel.FLOOR_DURATION_MS,
                    levelDb = state.liveLevelDb,
                    onCancel = viewModel::cancel,
                )

                CalibrationPhase.IDLE -> Button(
                    onClick = { haptics.confirm(); viewModel.startFloorMeasurement() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Zmierz tło (15 s)") }

                else -> {
                    ResultCard(state)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::startFloorMeasurement,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zmierz tło jeszcze raz") }
                }
            }

            if (state.phase == CalibrationPhase.FLOOR_READY ||
                state.phase == CalibrationPhase.VERIFYING ||
                state.phase == CalibrationPhase.VERIFIED
            ) {
                SectionHeader("Krok 2 — weryfikacja progu")
                Text(
                    "Powiedz coś cicho, tak jak mamrocze się przez sen — z miejsca, gdzie śpisz. " +
                        "Sprawdzimy, czy przy tym progu bramka naprawdę by się wyzwoliła.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (state.phase == CalibrationPhase.VERIFYING) {
                    MeasuringCard(
                        caption = "Mów cicho…",
                        remainingMs = state.remainingMs,
                        totalMs = CalibrationViewModel.SPEECH_DURATION_MS,
                        levelDb = state.liveLevelDb,
                        onCancel = viewModel::cancel,
                    )
                } else {
                    if (state.phase == CalibrationPhase.VERIFIED) {
                        VerificationCard(state)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(
                        onClick = { haptics.confirm(); viewModel.startVerification() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.phase == CalibrationPhase.VERIFIED) "Powtórz test (5 s)" else "Test mowy (5 s)") }
                }

                SectionHeader("Próg do zapisania")
                ParameterSlider(
                    label = "triggerDeltaDb",
                    valueText = "${state.suggestedDeltaDb.toInt()} dB",
                    value = state.suggestedDeltaDb,
                    range = 6f..24f,
                    steps = 17,
                    onValueChange = { viewModel.adjustSuggestion(it) },
                    description = "Próg względny: tło ${Format.db(state.measuredFloorDb)} + " +
                        "${state.suggestedDeltaDb.toInt()} dB = " +
                        Format.db(state.measuredFloorDb + state.suggestedDeltaDb),
                )
                ParameterSlider(
                    label = "Podłoga progu (minTriggerDb)",
                    valueText = Format.db(state.suggestedMinTriggerDb),
                    value = state.suggestedMinTriggerDb,
                    range = NightVoxSettings.MIN_TRIGGER_RANGE_DB,
                    steps = 39,
                    onValueChange = { viewModel.adjustMinTrigger(it) },
                    description = "Próg nigdy nie zejdzie poniżej tej wartości, choćby pokój był " +
                        "bardzo cichy. Wyliczona z Twojego głosu: 10 dB pod szczytem " +
                        "${Format.db(state.speechPeakDb)}. Realny próg tej nocy: " +
                        Format.db(state.thresholdDb) + ".",
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { haptics.confirm(); viewModel.applySuggestion(onBack) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Zapisz próg i wróć") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MeasuringCard(
    caption: String,
    remainingMs: Long,
    totalMs: Long,
    levelDb: Float,
    onCancel: () -> Unit,
) {
    NightCard {
        Text(caption, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "${(remainingMs / 1000) + 1} s",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { 1f - (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Poziom: ${Format.db(levelDb)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text("Przerwij") }
    }
}

@Composable
private fun ResultCard(state: CalibrationState) {
    NightCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatTile("tło (mediana)", Format.db(state.measuredFloorDb))
            StatTile("cisza p95", Format.db(state.quietPercentileDb))
            StatTile("próg", Format.db(state.thresholdDb))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Propozycja bierze rozrzut ciszy (p95 − mediana) i dokłada 6 dB zapasu. " +
                "Zbyt niski próg to kilkaset klipów z trzaskami, zbyt wysoki — cicha noc mimo mówienia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerificationCard(state: CalibrationState) {
    val ok = state.wouldTrigger
    NightCard(
        container = if (ok) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
            )
            Text(
                text = if (ok) "Ten próg złapałby tę wypowiedź" else "Ten próg by NIE zadziałał",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatTile("szczyt mowy", Format.db(state.speechPeakDb))
            StatTile("średnia", Format.db(state.speechMeanDb))
            StatTile("ramek nad progiem", state.longestRunFrames.toString())
            StatTile("ocena mowy", state.speechScore?.let { Format.score(it) } ?: "—")
        }
        state.speechScore?.let { score ->
            Spacer(Modifier.height(12.dp))
            Text(
                "Filtr mowy ocenił tę wypowiedź na ${Format.score(score)}. Próg filtru ustaw " +
                    "wyraźnie poniżej tej wartości — inaczej wytnie dokładnie to, co chcesz łapać. " +
                    "Klipy z samym oddechem dostają zwykle poniżej 0,30.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!ok) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Obniż próg suwakiem poniżej i powtórz test. Jeśli nawet 6 dB nie wystarcza, " +
                    "problem jest w odległości albo w tym, że telefon leży mikrofonem do materaca.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
