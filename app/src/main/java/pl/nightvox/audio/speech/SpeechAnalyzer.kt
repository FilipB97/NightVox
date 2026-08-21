package pl.nightvox.audio.speech

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analiza mowy w czystym Kotlinie: okno po oknie liczy cechy widmowe, na końcu klipu składa
 * je w jedną ocenę.
 *
 * Pracuje wyłącznie na tym PCM, które i tak trafia do enkodera — czyli przez ułamek nocy.
 * Bramka RMS chodzi dalej na każdej ramce i to ona decyduje, kiedy w ogóle jest co liczyć.
 *
 * Nie jest thread-safe; żyje na wątku enkodera razem z [pl.nightvox.encode.ClipWriter].
 */
class SpeechAnalyzer(
    private val sampleRate: Int = 16_000,
    private val windowSamples: Int = DEFAULT_WINDOW,
    private val hopSamples: Int = DEFAULT_HOP,
    /** Powyżej tej oceny okno liczy się jako mowa przy sumowaniu [SpeechScore.speechMs]. */
    private val speechThreshold: Float = DEFAULT_THRESHOLD,
    /**
     * Ile kolejnych okien musi utrzymać ocenę, żeby się liczyła.
     *
     * Bez tego oceną klipu jest maksimum po **wszystkich** oknach, a klip z ośmiu sekund
     * oddechu ma ich kilkaset. Maksimum z kilkuset zaszumionych ocen to statystyka wartości
     * skrajnych, nie własność dźwięku — na prawdziwej nocy mediana okna wynosiła 0,08, a
     * maksimum klipu 0,77. Pięć okien to ok. 160 ms, czyli sylaba.
     */
    private val runWindows: Int = DEFAULT_RUN_WINDOWS,
) {
    private val fft = Fft(windowSamples)
    private val window = Fft.hann(windowSamples)
    private val pitchTracker = PitchTracker(sampleRate, windowSamples)

    private val buffer = FloatArray(windowSamples)
    private var filled = 0

    private val windowed = FloatArray(windowSamples)
    private val spectrum = FloatArray(fft.bins)
    private val bands = FloatArray(BAND_COUNT)

    /**
     * Sześć ostatnich wektorów pasm. Zmienność liczymy między średnią z trzech najnowszych a
     * średnią z trzech poprzednich, a nie między sąsiednimi oknami: pojedyncze okno szumu ma
     * losowe pasma, więc różnica okno-do-okna wychodzi duża dla oddechu, który przecież
     * niczego nie artykułuje. Uśrednienie po czasie tę losowość zbija, a ruch formantów —
     * trwający 50–100 ms — przeżywa.
     */
    private val bandHistory = Array(FLUX_HISTORY) { FloatArray(BAND_COUNT) }
    private var historyCount = 0

    private val bandEdges = logSpacedEdges()
    private val binHz = sampleRate.toFloat() / windowSamples

    private val scores = ArrayList<Float>()
    private val envelope = ArrayList<Float>()
    private val pitches = ArrayList<Float>()
    private var sumHiRatio = 0.0
    private var sumFlatness = 0.0
    private var sumFlux = 0.0

    val hopMs: Long = hopSamples.toLong() * 1000L / sampleRate

    /**
     * Poniżej tego poziomu okno nie jest w ogóle analizowane. Ustawiane na próg, przy którym
     * bramka wyzwoliła klip: pre-roll i hangover to razem siedem sekund tła doklejonego do
     * każdego nagrania i nie ma powodu szukać w nich mowy.
     */
    private var levelFloorDb: Float = NO_FLOOR

    /** Nowy klip: kontekst poprzedniego nie ma tu nic do rzeczy. */
    fun reset(levelFloorDb: Float = NO_FLOOR) {
        this.levelFloorDb = levelFloorDb
        filled = 0
        historyCount = 0
        scores.clear()
        envelope.clear()
        pitches.clear()
        sumHiRatio = 0.0
        sumFlatness = 0.0
        sumFlux = 0.0
    }

    /** Karmi analizator kolejnym kawałkiem PCM16 — tym samym, który idzie do enkodera. */
    fun feed(samples: ShortArray, count: Int = samples.size) {
        var offset = 0
        while (offset < count) {
            val taken = minOf(windowSamples - filled, count - offset)
            for (i in 0 until taken) buffer[filled + i] = samples[offset + i] / SCALE
            filled += taken
            offset += taken
            if (filled == windowSamples) {
                analyzeWindow()
                // Przesuwamy o hop, resztę zostawiamy jako początek następnego okna.
                System.arraycopy(buffer, hopSamples, buffer, 0, windowSamples - hopSamples)
                filled = windowSamples - hopSamples
            }
        }
    }

    /** `null`, gdy klip był za krótki na choćby jedno okno. */
    fun finish(): SpeechScore? {
        if (scores.isEmpty()) return null

        val smoothed = smooth(scores)
        val modulation = modulationRatio()
        val factor = MODULATION_FLOOR + (1f - MODULATION_FLOOR) *
            SpeechScorer.ramp(modulation, MODULATION_LOW, MODULATION_HIGH)

        var speechWindows = 0
        for (value in smoothed) {
            if (value * factor >= speechThreshold) speechWindows++
        }
        // Ocena klipu = najwyższa wartość, która utrzymała się przez [runWindows] okien.
        var max = 0f
        if (smoothed.size >= runWindows) {
            for (start in 0..smoothed.size - runWindows) {
                var lowest = Float.MAX_VALUE
                for (i in start until start + runWindows) {
                    if (smoothed[i] < lowest) lowest = smoothed[i]
                }
                val corrected = lowest * factor
                if (corrected > max) max = corrected
            }
        }

        val n = scores.size
        return SpeechScore(
            score = max,
            speechMs = speechWindows * hopMs,
            analyzedMs = n * hopMs,
            modulation = modulation,
            meanHiRatio = (sumHiRatio / n).toFloat(),
            meanFlatness = (sumFlatness / n).toFloat(),
            meanFlux = (sumFlux / n).toFloat(),
            medianPitchHz = medianPitch(),
        )
    }

    // --- wnętrze ---

    private fun analyzeWindow() {
        var sumSquares = 0.0
        for (i in 0 until windowSamples) {
            val s = buffer[i]
            sumSquares += s.toDouble() * s
        }
        val rms = sqrt(sumSquares / windowSamples)
        val energyDb = (20.0 * log10(maxOf(rms, 1e-6))).toFloat()
        // Cisza między zdarzeniami nie jest kandydatem na mowę — i nie warto jej liczyć.
        if (energyDb < levelFloorDb) return

        for (i in 0 until windowSamples) windowed[i] = buffer[i] * window[i]

        fft.powerSpectrum(windowed, spectrum)
        val hiRatio = highBandRatio()
        fillBands()
        val flatness = flatness()
        val flux = pushHistoryAndComputeFlux()

        val pitch = pitchTracker.estimate(buffer, windowSamples)

        val features = SpeechFeatures(
            energyDb = energyDb,
            pitchHz = pitch.hz,
            pitchStrength = pitch.strength,
            hiRatio = hiRatio,
            flatness = flatness,
            flux = flux,
        )
        scores += SpeechScorer.score(features)
        envelope += energyDb
        if (pitch.strength >= PITCH_REPORT_MIN) pitches += pitch.hz
        sumHiRatio += hiRatio
        sumFlatness += flatness
        sumFlux += flux
    }

    private fun binOf(hz: Float): Int = (hz / binHz).toInt().coerceIn(0, fft.bins - 1)

    private fun highBandRatio(): Float {
        var total = 0.0
        var high = 0.0
        val from = binOf(TOTAL_LOW_HZ)
        val to = binOf(TOTAL_HIGH_HZ)
        val highFrom = binOf(HIGH_LOW_HZ)
        val highTo = binOf(HIGH_HIGH_HZ)
        for (k in from..to) {
            val p = spectrum[k].toDouble()
            total += p
            if (k in highFrom..highTo) high += p
        }
        return if (total <= 0.0) 0f else (high / total).toFloat()
    }

    /**
     * Energia w 16 pasmach 1/4-oktawowych, znormalizowana do sumy 1.
     *
     * Uśrednianie po pasmach, a nie po prążkach, jest tu celowe: szum ma losowe prążki, więc
     * liczona wprost różnica widm robi z oddechu „bardzo zmienne widmo”. Po scaleniu w pasma
     * ta losowość znika, a ruch formantów — nie.
     */
    private fun fillBands() {
        var total = 0.0
        for (b in 0 until BAND_COUNT) {
            var sum = 0.0
            val from = binOf(bandEdges[b])
            val to = maxOf(from, binOf(bandEdges[b + 1]) - 1)
            for (k in from..to) sum += spectrum[k]
            val width = (to - from + 1)
            val value = sum / width
            bands[b] = value.toFloat()
            total += value
        }
        if (total <= 0.0) {
            bands.fill(1f / BAND_COUNT)
            return
        }
        for (b in 0 until BAND_COUNT) bands[b] = (bands[b] / total).toFloat()
    }

    private fun flatness(): Float {
        var logSum = 0.0
        var sum = 0.0
        for (b in 0 until BAND_COUNT) {
            val v = bands[b] + EPSILON
            logSum += ln(v)
            sum += v
        }
        val geometric = exp(logSum / BAND_COUNT)
        val arithmetic = sum / BAND_COUNT
        return if (arithmetic <= 0.0) 0f else (geometric / arithmetic).toFloat().coerceIn(0f, 1f)
    }

    /** Dokłada bieżące pasma do historii i zwraca zmienność widma; 0, dopóki historii brak. */
    private fun pushHistoryAndComputeFlux(): Float {
        val slot = bandHistory[historyCount % FLUX_HISTORY]
        System.arraycopy(bands, 0, slot, 0, BAND_COUNT)
        historyCount++
        if (historyCount < FLUX_HISTORY) return 0f

        var distance = 0.0
        val half = FLUX_HISTORY / 2
        for (b in 0 until BAND_COUNT) {
            var older = 0f
            var newer = 0f
            for (k in 0 until half) {
                // historyCount wskazuje slot *po* najnowszym; cofamy się o k pozycji.
                newer += bandHistory[(historyCount - 1 - k + FLUX_HISTORY * 2) % FLUX_HISTORY][b]
                older += bandHistory[(historyCount - 1 - half - k + FLUX_HISTORY * 2) % FLUX_HISTORY][b]
            }
            distance += abs(newer - older) / half
        }
        // L1 między dwoma rozkładami mieści się w [0, 2].
        return (distance / 2.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Udział pasma 2,5–8 Hz w widmie obwiedni energii.
     *
     * Mowa ma rytm sylab (3–6 Hz) — to jedna z niewielu rzeczy, które trzymają się bez
     * względu na język i głos. Chrapanie moduluje się rytmem oddechu (0,2–0,5 Hz), a spokojny
     * oddech prawie wcale. Liczone raz na klip, na obwiedni próbkowanej co [hopMs].
     */
    private fun modulationRatio(): Float {
        val n = envelope.size
        if (n < MIN_MODULATION_WINDOWS) return NEUTRAL_MODULATION

        var mean = 0.0
        for (v in envelope) mean += v
        mean /= n

        val frameRate = 1000.0 / hopMs
        var inBand = 0.0
        var total = 0.0
        var hz = MODULATION_MIN_HZ
        while (hz <= MODULATION_MAX_HZ + 1e-6) {
            var re = 0.0
            var im = 0.0
            val step = 2.0 * Math.PI * hz / frameRate
            for (i in 0 until n) {
                val v = envelope[i] - mean
                re += v * cos(step * i)
                im += v * sin(step * i)
            }
            val magnitude = sqrt(re * re + im * im)
            total += magnitude
            if (hz >= SYLLABLE_MIN_HZ && hz <= SYLLABLE_MAX_HZ) inBand += magnitude
            hz += MODULATION_STEP_HZ
        }
        return if (total <= 0.0) NEUTRAL_MODULATION else (inBand / total).toFloat()
    }

    private fun medianPitch(): Float {
        if (pitches.isEmpty()) return 0f
        val sorted = pitches.sorted()
        return sorted[sorted.size / 2]
    }

    private fun smooth(values: List<Float>): FloatArray {
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var sum = 0f
            var count = 0
            for (j in maxOf(0, i - SMOOTH_RADIUS)..minOf(values.size - 1, i + SMOOTH_RADIUS)) {
                sum += values[j]
                count++
            }
            out[i] = sum / count
        }
        return out
    }

    private fun logSpacedEdges(): FloatArray {
        val ratio = Math.pow((BAND_HIGH_HZ / BAND_LOW_HZ).toDouble(), 1.0 / BAND_COUNT)
        return FloatArray(BAND_COUNT + 1) { i -> (BAND_LOW_HZ * Math.pow(ratio, i.toDouble())).toFloat() }
    }

    companion object {
        /** 64 ms @ 16 kHz — dwa okresy najniższego tonu, jaki nas interesuje. */
        const val DEFAULT_WINDOW = 1024

        /** 32 ms — okna zachodzą na siebie w połowie. */
        const val DEFAULT_HOP = 512

        const val DEFAULT_THRESHOLD = 0.5f

        /** 5 okien ≈ 160 ms, czyli sylaba. */
        const val DEFAULT_RUN_WINDOWS = 5

        /** Brak dolnego ograniczenia poziomu — analizuj wszystko. */
        const val NO_FLOOR = -1000f

        /** Trzy najnowsze okna kontra trzy poprzednie. */
        private const val FLUX_HISTORY = 6

        private const val SCALE = 32768f
        private const val EPSILON = 1e-9
        private const val BAND_COUNT = 16
        private const val BAND_LOW_HZ = 250f
        private const val BAND_HIGH_HZ = 4000f
        private const val TOTAL_LOW_HZ = 45f
        private const val TOTAL_HIGH_HZ = 6000f
        private const val HIGH_LOW_HZ = 300f
        private const val HIGH_HIGH_HZ = 4000f

        /** Wygładzanie ±2 okna (~160 ms) — sylaba, a nie pojedynczy trzask. */
        private const val SMOOTH_RADIUS = 2

        private const val PITCH_REPORT_MIN = 0.35f

        private const val MIN_MODULATION_WINDOWS = 32
        private const val MODULATION_MIN_HZ = 0.5f
        private const val MODULATION_MAX_HZ = 15f
        private const val MODULATION_STEP_HZ = 0.5f
        private const val SYLLABLE_MIN_HZ = 2.5f
        private const val SYLLABLE_MAX_HZ = 8f

        /** Klip za krótki na ocenę rytmu dostaje wartość neutralną, nie karę. */
        private const val NEUTRAL_MODULATION = 0.5f

        /** Ile zostaje z oceny przy zerowym rytmie sylab. */
        private const val MODULATION_FLOOR = 0.65f
        private const val MODULATION_LOW = 0.35f
        private const val MODULATION_HIGH = 0.60f
    }
}
