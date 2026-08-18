package pl.nightvox.audio.speech

/** Cechy jednego okna analizy (64 ms). Wszystko bezwymiarowe poza [pitchHz] i [energyDb]. */
data class SpeechFeatures(
    val energyDb: Float,
    /** Ton podstawowy w Hz albo 0, gdy okno jest nieokresowe. */
    val pitchHz: Float,
    /** Znormalizowana autokorelacja w szczycie: 0 = szum, 1 = idealnie okresowe. */
    val pitchStrength: Float,
    /** Udział pasma 300–4000 Hz w energii 45–6000 Hz. */
    val hiRatio: Float,
    /** Płaskość widma na 16 pasmach 250–4000 Hz: 1 = szum szerokopasmowy, 0 = ostre formanty. */
    val flatness: Float,
    /** Zmiana kształtu widma względem poprzedniego okna, 0..1. */
    val flux: Float,
)

/**
 * Zamienia cechy okna na „jak bardzo to brzmi jak mowa”, 0..1.
 *
 * Problem, który ta funkcja rozwiązuje, wygląda tak: bramka RMS przepuszcza wszystko, co
 * jest głośniejsze od tła, a nocą głośniejsze od tła są trzy rzeczy — mowa, oddech i
 * chrapanie. Każda z nich ma inną sygnaturę:
 *
 * | | oddech | chrapanie | mowa |
 * |---|---|---|---|
 * | okresowość | brak | silna | silna (dźwięczne) |
 * | ton podstawowy | — | 25–90 Hz | 85–300 Hz |
 * | energia > 300 Hz | duża (szum) | mała | duża (formanty) |
 * | płaskość widma | wysoka | niska | niska |
 * | zmienność widma | znikoma | znikoma | duża (artykulacja) |
 *
 * Żadna pojedyncza cecha nie rozdziela wszystkich trzech: `hiRatio` nie odróżnia oddechu
 * od mowy, okresowość nie odróżnia chrapania od mowy, a płaskość nie odróżnia chrapania.
 * Dopiero razem tworzą trzy różne wzorce — stąd iloczyn, a nie suma: chrapanie musi dać
 * się **wyzerować**, nawet jeśli akurat dostanie parę punktów za coś innego.
 *
 * Wagi są zgadnięte, nie wytrenowane. Dlatego wynik ląduje w bazie i w logu diagnostycznym
 * dla **każdego** klipu, a klipy pod progiem trafiają do kosza, a nie do kasza.
 */
object SpeechScorer {

    /** Udział trzech cech „widmo wygląda jak głoska” w ocenie bazowej. */
    private const val WEIGHT_FORMANT = 0.40f
    private const val WEIGHT_ARTICULATION = 0.30f
    private const val WEIGHT_SHARPNESS = 0.30f

    /** Ile zostaje z oceny, gdy nie ma tonu krtaniowego — szept to nadal mowa. */
    private const val UNVOICED_FLOOR = 0.55f

    /** Ile odbiera buczenie poniżej [PitchTracker.SNORE_MAX_HZ]. */
    private const val SNORE_PENALTY = 0.85f

    fun score(f: SpeechFeatures): Float {
        val formant = ramp(f.hiRatio, 0.20f, 0.55f)
        val articulation = ramp(f.flux, 0.08f, 0.35f)
        val sharpness = ramp(1f - f.flatness, 0.25f, 0.65f)
        val tonal = ramp(f.pitchStrength, 0.25f, 0.55f)
        val voiceRange = pitchWeight(f.pitchHz)

        val base = WEIGHT_FORMANT * formant +
            WEIGHT_ARTICULATION * articulation +
            WEIGHT_SHARPNESS * sharpness
        val voiced = UNVOICED_FLOOR + (1f - UNVOICED_FLOOR) * tonal * voiceRange
        val snore = tonal * (1f - voiceRange)

        return (base * voiced * (1f - SNORE_PENALTY * snore)).coerceIn(0f, 1f)
    }

    /**
     * Ile „głosu” jest w tej wysokości tonu. Zero poniżej 80 Hz — tak nisko drga podniebienie
     * miękkie, nie fałdy głosowe.
     */
    fun pitchWeight(hz: Float): Float = when {
        hz <= 0f -> 0f
        hz < 80f -> 0f
        hz < 110f -> ramp(hz, 80f, 110f)
        hz <= 300f -> 1f
        else -> 1f - 0.7f * ramp(hz, 300f, 400f)
    }

    fun ramp(value: Float, low: Float, high: Float): Float =
        ((value - low) / (high - low)).coerceIn(0f, 1f)
}

/** Ocena całego klipu — to, co ląduje w `Clip.vadScore` i w logu diagnostycznym. */
data class SpeechScore(
    /** Najwyższa wygładzona ocena okna po korekcie modulacyjnej, 0..1. */
    val score: Float,
    /** Ile audio przekroczyło próg (po wygładzeniu). */
    val speechMs: Long,
    val analyzedMs: Long,
    /** Udział pasma 2,5–8 Hz w widmie obwiedni — rytm sylab. */
    val modulation: Float,
    val meanHiRatio: Float,
    val meanFlatness: Float,
    val meanFlux: Float,
    /** Mediana tonu z okien okresowych; 0, gdy klip jest nieokresowy. */
    val medianPitchHz: Float,
) {
    /** Jednolinijkowe podsumowanie do logu — po nocy to jest jedyne źródło prawdy o strojeniu. */
    fun describe(): String = "score=%.2f mowa=%dms mod=%.2f hi=%.2f flat=%.2f flux=%.2f f0=%.0fHz".format(
        java.util.Locale.US, score, speechMs, modulation, meanHiRatio, meanFlatness, meanFlux, medianPitchHz,
    )
}
