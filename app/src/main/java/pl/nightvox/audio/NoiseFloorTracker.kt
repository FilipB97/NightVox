package pl.nightvox.audio

/**
 * Adaptacyjne tło szumu (§4.2).
 *
 * Warm-up: pierwsze [warmupFrames] ramek zbieramy i bierzemy **medianę**, nie EMA — trzask
 * ładowarki albo skrzypnięcie łóżka na starcie sesji nie może zatruć progu na całą noc.
 *
 * Po warm-upie [update] wolno wołać **wyłącznie gdy bramka jest zamknięta** (stan IDLE),
 * inaczej długa wypowiedź podniosłaby tło i sama się wyciszyła.
 * Asymetryczna EMA: szybki spadek (tło ucichło), wolny wzrost (nie daj się nabrać na mowę).
 */
class NoiseFloorTracker(
    private val warmupFrames: Int,
    private val fallAlpha: Float = 0.05f,
    private val riseAlpha: Float = 0.002f,
    initialFloorDb: Float = -60f,
) {
    private val warmupSamples = FloatArray(maxOf(warmupFrames, 1))
    private var warmupCount = 0

    var floorDb: Float = initialFloorDb
        private set

    var isWarmedUp: Boolean = warmupFrames <= 0
        private set

    /** Ile ramek warm-upu jeszcze zostało (do UI). */
    val warmupRemaining: Int get() = (warmupFrames - warmupCount).coerceAtLeast(0)

    /**
     * Karmi tracker kolejną ramką. W fazie warm-up zbiera próbki do mediany, po niej
     * aktualizuje EMA. Wywołuj tylko wtedy, gdy bramka jest zamknięta.
     */
    fun update(dbfs: Float) {
        if (!isWarmedUp) {
            if (warmupCount < warmupSamples.size) {
                warmupSamples[warmupCount] = dbfs
            }
            warmupCount++
            if (warmupCount >= warmupFrames) finishWarmup()
            return
        }
        val alpha = if (dbfs < floorDb) fallAlpha else riseAlpha
        floorDb += alpha * (dbfs - floorDb)
    }

    /** Kończy warm-up przed czasem (np. skrócona kalibracja) i ustala tło jako medianę. */
    fun finishWarmup() {
        if (isWarmedUp) return
        val n = minOf(warmupCount, warmupSamples.size)
        if (n > 0) {
            val copy = warmupSamples.copyOf(n)
            copy.sort()
            floorDb = if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2f
        }
        isWarmedUp = true
    }
}
