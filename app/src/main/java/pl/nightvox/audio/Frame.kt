package pl.nightvox.audio

/**
 * Pojedyncza ramka PCM 16-bit mono.
 *
 * [samples] jest własnością ramki — producent (AudioCapture) alokuje nową tablicę na każdą
 * ramkę i nie modyfikuje jej po wysłaniu. Przy 16 kHz / 20 ms to 320 shortów, ok. 32 kB/s
 * alokacji; wymiana tego na pooling jest przedwczesną optymalizacją i psuje testowalność.
 */
class Frame(
    val samples: ShortArray,
    val timestampMs: Long,
) {
    val sampleCount: Int get() = samples.size
}
