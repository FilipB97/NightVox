package pl.nightvox.audio

import kotlin.math.sqrt

enum class GateState {
    /** Zbieranie tła, bramka nieaktywna. */
    WARMUP,

    /** Cisza — tło się adaptuje, czekamy na trigger. */
    IDLE,

    /** Nagrywamy, poziom powyżej progu. */
    RECORDING,

    /** Poziom spadł, ale wciąż nagrywamy — pauza w mowie bywa długa. */
    HANGOVER,

    /**
     * Klip logicznie zamknięty, plik jeszcze otwarty. Ramki lecą do bufora scalania:
     * jeśli w ciągu `mergeGapMs` padnie nowy trigger, dopisujemy je do tego samego pliku
     * (§4.3), inaczej znikają i klip jest finalizowany.
     */
    LINGER,
}

/** Statystyki gotowego klipu — trafiają do wiersza `Clip` w Room. */
data class ClipStats(
    val startedAtMs: Long,
    val durationMs: Long,
    val voicedMs: Long,
    val peakDb: Float,
    val meanDb: Float,
    /** Ile wypowiedzi scalono w ten plik (1 = brak scalania). */
    val segments: Int,
)

enum class DiscardReason {
    /** Sumaryczny czas ramek powyżej progu poniżej `minVoicedMs` — trzask, nie mowa. */
    TOO_SHORT,
}

sealed interface GateAction {
    /**
     * Otwórz nowy plik klipu. [thresholdDb] to próg, przy którym zdarzenie się wyzwoliło —
     * analiza mowy używa go, żeby nie oceniać pre-rolla i hangoveru, czyli siedmiu sekund
     * tła doklejonych do każdego klipu.
     */
    data class OpenClip(val startedAtMs: Long, val thresholdDb: Float = LevelMeter.MIN_DBFS) : GateAction

    /** Dopisz PCM do bieżącego pliku. Tablica należy już do odbiorcy. */
    class Write(val samples: ShortArray) : GateAction

    /** Zamknij i zachowaj. */
    data class CloseClip(val stats: ClipStats) : GateAction

    /** Zamknij i skasuj plik. */
    data class DiscardClip(val reason: DiscardReason, val stats: ClipStats) : GateAction
}

/**
 * Bramka progowa z pre-rollem, hangoverem i scalaniem (§4.3 planu).
 *
 * Czysty Kotlin, zero zależności od Androida — karmiona [Frame] zwraca listę akcji dla
 * zapisywacza. Cała logika decyzyjna projektu siedzi tutaj i jest testowalna na JVM.
 *
 * Klasa **nie** jest thread-safe: żyje na jednym wątku pipeline'u.
 */
class Gate(
    val config: GateConfig,
    private val floorTracker: NoiseFloorTracker = NoiseFloorTracker(config.warmupFrames),
    private val ringBuffer: RingBuffer = RingBuffer(maxOf(config.preRollSamples, config.frameSamples)),
) {
    var state: GateState = if (floorTracker.isWarmedUp) GateState.IDLE else GateState.WARMUP
        private set

    /** Poziom ostatniej ramki w dBFS — do live metera. */
    var lastLevelDb: Float = LevelMeter.MIN_DBFS
        private set

    val floorDb: Float get() = floorTracker.floorDb
    /**
     * Próg wyzwolenia: tło + delta, ale **nigdy niżej** niż [GateConfig.minTriggerDb].
     *
     * Sam próg względny wystarcza dopóki tło jest jakieś. W cichej sypialni zmierzone tło
     * schodzi do −78 dBFS, a wtedy „tło + 15 dB" to −63 dBFS — poziom, na którym nie ma już
     * niczego słyszalnego, tylko szum własny mikrofonu i szelest pościeli. Noc na takim progu
     * daje trzysta nagrań, na których nic nie słychać. Bezwzględna podłoga jest twardym
     * ograniczeniem od dołu i nie zależy od tego, jak cichy okaże się pokój.
     */
    val triggerThresholdDb: Float
        get() = maxOf(floorTracker.floorDb + config.triggerDeltaDb, config.minTriggerDb)
    val releaseThresholdDb: Float get() = triggerThresholdDb - config.releaseHysteresisDb
    val isWarmingUp: Boolean get() = state == GateState.WARMUP

    /**
     * Ile jeszcze warm-upu zostało, liczone w **przetworzonych ramkach**, a nie w czasie od
     * startu sesji. Mikrofon otwiera się z opóźnieniem i potrafi paść w trakcie — odliczanie
     * po zegarku pokazywałoby wtedy zero, mimo że tło wciąż nie jest zmierzone.
     */
    val warmupRemainingMs: Long get() = floorTracker.warmupRemaining * config.frameMs

    // --- stan bieżącego klipu ---
    private var clipStartedAtMs = 0L
    private var clipSamples = 0L
    private var clipVoicedFrames = 0L
    private var clipSumSquares = 0.0
    private var clipPeakLinear = 0
    private var clipSegments = 0

    // --- liczniki maszyny stanów ---
    private var aboveRun = 0
    private var floorBlockedRun = 0

    /**
     * Ile zdarzeń przeszłoby próg względny, ale zatrzymała je [GateConfig.minTriggerDb].
     *
     * Bez tego licznika podłoga jest niewidoczna: przy zbyt wysokiej wartości noc kończy się
     * pustą listą i nie wiadomo, czy było cicho, czy filtr zjadł wszystko. Ta liczba trafia do
     * logu diagnostycznego na koniec sesji.
     */
    var eventsBlockedByFloor = 0
        private set
    private var hangoverRun = 0
    private var lingerRun = 0

    // --- bufor okna scalania ---
    private val pending = ArrayList<ShortArray>()
    private var pendingVoicedFrames = 0L

    /** Zdarzenia, które przeszłyby próg względny, ale nie przeszły bezwzględnej podłogi. */
    private fun countFloorBlocked(db: Float) {
        if (state != GateState.IDLE) {
            floorBlockedRun = 0
            return
        }
        val relative = floorTracker.floorDb + config.triggerDeltaDb
        if (db > relative && db <= config.minTriggerDb) {
            floorBlockedRun++
            if (floorBlockedRun == config.attackFrames) eventsBlockedByFloor++
        } else if (db <= relative) {
            floorBlockedRun = 0
        }
    }

    /**
     * Przetwarza jedną ramkę. Zwraca akcje do wykonania przez zapisywacz — pusta lista
     * jest zdecydowanie najczęstszym wynikiem (cisza).
     */
    fun process(frame: Frame): List<GateAction> {
        ringBuffer.write(frame.samples)

        val rms = LevelMeter.rms(frame.samples)
        val db = LevelMeter.toDbfs(rms)
        lastLevelDb = db

        countFloorBlocked(db)

        val actions = ArrayList<GateAction>(2)
        val emit: (GateAction) -> Unit = actions::add

        when (state) {
            GateState.WARMUP -> {
                floorTracker.update(db)
                if (floorTracker.isWarmedUp) state = GateState.IDLE
            }

            GateState.IDLE -> {
                floorTracker.update(db)
                aboveRun = if (db > triggerThresholdDb) aboveRun + 1 else 0
                if (aboveRun >= config.attackFrames) {
                    openClip(frame.timestampMs, emit)
                    state = GateState.RECORDING
                    aboveRun = 0
                }
            }

            GateState.RECORDING -> {
                appendToClip(frame, rms, db, emit)
                if (db < releaseThresholdDb) {
                    hangoverRun = 1
                    state = GateState.HANGOVER
                }
                checkMaxClip(emit)
            }

            GateState.HANGOVER -> {
                appendToClip(frame, rms, db, emit)
                if (db > triggerThresholdDb) {
                    hangoverRun = 0
                    state = GateState.RECORDING
                } else {
                    hangoverRun++
                    if (hangoverRun >= config.hangoverFrames) {
                        state = GateState.LINGER
                        lingerRun = 0
                        aboveRun = 0
                        clearPending()
                    }
                }
                checkMaxClip(emit)
            }

            GateState.LINGER -> {
                pending.add(frame.samples)
                if (db > releaseThresholdDb) pendingVoicedFrames++
                aboveRun = if (db > triggerThresholdDb) aboveRun + 1 else 0
                lingerRun++

                if (aboveRun >= config.attackFrames) {
                    // Nowa wypowiedź w oknie scalania — dopisujemy do tego samego pliku.
                    flushPending(emit)
                    clipSegments++
                    state = GateState.RECORDING
                    aboveRun = 0
                    hangoverRun = 0
                    checkMaxClip(emit)
                } else if (lingerRun >= config.mergeGapFrames) {
                    clearPending()
                    finalizeClip(emit)
                    state = GateState.IDLE
                }
            }
        }

        return actions
    }

    /**
     * Kończy sesję: domyka klip, jeśli jakiś jest otwarty. Ramki z okna scalania są
     * odrzucane — to z definicji cisza po ostatniej wypowiedzi.
     */
    fun flush(): List<GateAction> {
        val actions = ArrayList<GateAction>(2)
        when (state) {
            GateState.RECORDING, GateState.HANGOVER, GateState.LINGER -> {
                clearPending()
                finalizeClip(actions::add)
            }
            else -> Unit
        }
        state = if (floorTracker.isWarmedUp) GateState.IDLE else GateState.WARMUP
        aboveRun = 0
        return actions
    }

    /**
     * Twardy reset po restarcie capture (§6.2): stan bramki i bufor pre-rollu przestają
     * mieć sens, ale zmierzone tło zostaje — pokój się nie zmienił.
     */
    fun resetAfterCaptureRestart(): List<GateAction> {
        val actions = flush()
        ringBuffer.clear()
        return actions
    }

    // --- wnętrze ---

    private fun openClip(timestampMs: Long, emit: (GateAction) -> Unit) {
        val preRoll = ringBuffer.snapshot()
        // Znacznik startu klipu cofnięty o pre-roll — plik zaczyna się wcześniej niż trigger.
        clipStartedAtMs = timestampMs - preRoll.size * 1000L / config.sampleRate
        clipSamples = 0
        clipVoicedFrames = config.attackFrames.toLong()
        clipSumSquares = 0.0
        clipPeakLinear = 0
        clipSegments = 1

        emit(GateAction.OpenClip(clipStartedAtMs, triggerThresholdDb))
        if (preRoll.isNotEmpty()) {
            accumulate(preRoll, preRoll.size)
            emit(GateAction.Write(preRoll))
        }
    }

    private fun appendToClip(frame: Frame, rms: Double, db: Float, emit: (GateAction) -> Unit) {
        accumulate(frame.samples, frame.sampleCount, rms)
        if (db > releaseThresholdDb) clipVoicedFrames++
        emit(GateAction.Write(frame.samples))
    }

    private fun flushPending(emit: (GateAction) -> Unit) {
        for (chunk in pending) {
            accumulate(chunk, chunk.size)
            emit(GateAction.Write(chunk))
        }
        clipVoicedFrames += pendingVoicedFrames
        clearPending()
    }

    private fun clearPending() {
        pending.clear()
        pendingVoicedFrames = 0
    }

    private fun accumulate(samples: ShortArray, count: Int, knownRms: Double? = null) {
        clipSamples += count
        clipSumSquares += if (knownRms != null) knownRms * knownRms * count else {
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            sum
        }
        val peak = LevelMeter.peak(samples, count)
        if (peak > clipPeakLinear) clipPeakLinear = peak
    }

    private fun checkMaxClip(emit: (GateAction) -> Unit) {
        if (currentClipDurationMs() >= config.maxClipMs) {
            finalizeClip(emit)
            state = GateState.IDLE
            aboveRun = 0
            hangoverRun = 0
        }
    }

    private fun currentClipDurationMs(): Long = clipSamples * 1000L / config.sampleRate

    private fun finalizeClip(emit: (GateAction) -> Unit) {
        val voicedMs = clipVoicedFrames * config.frameMs
        val stats = ClipStats(
            startedAtMs = clipStartedAtMs,
            durationMs = currentClipDurationMs(),
            voicedMs = voicedMs,
            peakDb = LevelMeter.toDbfs(clipPeakLinear.toDouble()),
            meanDb = if (clipSamples > 0) {
                LevelMeter.toDbfs(sqrt(clipSumSquares / clipSamples))
            } else {
                LevelMeter.MIN_DBFS
            },
            segments = clipSegments,
        )
        if (voicedMs < config.minVoicedMs) {
            emit(GateAction.DiscardClip(DiscardReason.TOO_SHORT, stats))
        } else {
            emit(GateAction.CloseClip(stats))
        }
        clipSamples = 0
        clipVoicedFrames = 0
        clipSumSquares = 0.0
        clipPeakLinear = 0
        clipSegments = 0
    }
}
