package pl.nightvox.audio.vad

import android.util.Log
import java.io.File

/**
 * Bezpiecznik na awarie VAD, których nie da się złapać w Kotlinie.
 *
 * ONNX Runtime to kod natywny: gdy przewróci się na SIGSEGV, proces ginie **bez** przejścia
 * przez `Thread.setDefaultUncaughtExceptionHandler`. Żadne `try/catch` tego nie obejmie, a
 * bez śladu w logu wygląda to jak „apka po prostu znika”.
 *
 * Dlatego zamiast łapać wyjątek zostawiamy plik-znacznik przed wejściem w natywny kod i
 * kasujemy go po czystym zakończeniu sesji. Znacznik zastany przy następnym starcie znaczy,
 * że poprzednia sesja nie doszła do końca — a etap zapisany w środku mówi, gdzie zginęła.
 */
class VadCrashGuard(private val marker: File) {

    /** Ładowanie modelu i tworzenie sesji ONNX — najbardziej podejrzany moment. */
    fun beginInit() = write(STAGE_INIT)

    /** Model wstał; od teraz awaria może mieć inne przyczyny niż sam VAD. */
    fun initSucceeded() = write(STAGE_ACTIVE)

    fun endCleanly() {
        runCatching { marker.delete() }
    }

    /** Etap, na którym zginęła poprzednia sesja, albo `null` gdy zakończyła się czysto. */
    fun previousFailureStage(): String? =
        runCatching { if (marker.isFile) marker.readText().trim() else null }.getOrNull()

    /**
     * Czy to na tyle jednoznaczna wina VAD, żeby wyłączyć go automatycznie.
     *
     * Tylko awaria w trakcie inicjalizacji. Sesja, która działała i zginęła później, równie
     * dobrze mogła zostać ubita przez ROM w środku nocy — wyłączanie VAD za cudze winy
     * byłoby cichym pogorszeniem działania.
     */
    fun previousFailureBlamesVad(): Boolean = previousFailureStage() == STAGE_INIT

    private fun write(stage: String) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(stage)
        }.onFailure { Log.w(TAG, "Nie udało się zapisać znacznika VAD", it) }
    }

    companion object {
        private const val TAG = "NightVox/VadGuard"
        const val STAGE_INIT = "init"
        const val STAGE_ACTIVE = "active"
        const val FILE_NAME = "vad-session.marker"
    }
}
