package pl.nightvox.audio.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (ONNX Runtime Mobile) — faza 3 planu.
 *
 * Model 16 kHz leży w `assets`, nic nie jest pobierane w runtime; apka dalej nie ma
 * uprawnienia INTERNET. Sesja jest jednowątkowa i żyje na wątku, który ją stworzył —
 * u nas na wątku enkodera, nigdy na wątku odczytu z mikrofonu.
 *
 * Model jest **stanowy**: [state] przenosi kontekst rekurencyjny między chunkami, więc
 * kolejność wywołań ma znaczenie, a każdy nowy klip musi zacząć od [reset].
 */
class SileroVad private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val sampleRate: Int,
) : AutoCloseable {

    private val state = FloatArray(STATE_LAYERS * STATE_SIZE)
    private val inputShape = longArrayOf(1, 0)

    /** Prawdopodobieństwo mowy dla jednego wejścia (kontekst + chunk) z [VadChunker]. */
    fun probability(input: FloatArray): Float {
        inputShape[1] = input.size.toLong()
        return try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), inputShape).use { inputTensor ->
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(state),
                    longArrayOf(STATE_LAYERS.toLong(), 1, STATE_SIZE.toLong()),
                ).use { stateTensor ->
                    OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(longArrayOf(sampleRate.toLong())),
                        longArrayOf(),
                    ).use { srTensor ->
                        session.run(
                            mapOf(
                                INPUT_AUDIO to inputTensor,
                                INPUT_STATE to stateTensor,
                                INPUT_SAMPLE_RATE to srTensor,
                            ),
                        ).use { result ->
                            val probability = (result[0].value as Array<*>).let { rows ->
                                (rows[0] as FloatArray)[0]
                            }
                            copyState(result[1].value)
                            probability
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Także Error (np. UnsatisfiedLinkError przy wadliwej bibliotece natywnej):
            // VAD jest dodatkiem, nagrywanie ma lecieć dalej.
            Log.w(TAG, "Inferencja VAD nie powiodła się", t)
            0f
        }
    }

    /** Zeruje stan rekurencyjny. Wołaj przed każdym nowym klipem. */
    fun reset() {
        state.fill(0f)
    }

    override fun close() {
        runCatching { session.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyState(value: Any?) {
        val layers = value as? Array<*> ?: return
        for (layer in layers.indices) {
            val batches = layers[layer] as? Array<*> ?: continue
            val values = batches.firstOrNull() as? FloatArray ?: continue
            val offset = layer * STATE_SIZE
            val count = minOf(values.size, STATE_SIZE)
            System.arraycopy(values, 0, state, offset, count)
        }
    }

    companion object {
        private const val TAG = "NightVox/Vad"
        const val ASSET_NAME = "silero_vad_16k.onnx"

        private const val INPUT_AUDIO = "input"
        private const val INPUT_STATE = "state"
        private const val INPUT_SAMPLE_RATE = "sr"
        private const val STATE_LAYERS = 2
        private const val STATE_SIZE = 128

        /**
         * Zwraca `null`, gdy modelu nie da się załadować. VAD jest funkcją dodatkową —
         * jego awaria nie może zatrzymać nagrywania, bo nagrywanie jest tu jedyną rzeczą,
         * której nie da się odtworzyć później.
         */
        fun create(context: Context, sampleRate: Int = 16_000): SileroVad? = try {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            SileroVad(environment, environment.createSession(bytes, options), sampleRate)
        } catch (e: Throwable) {
            Log.w(TAG, "Nie udało się załadować modelu VAD", e)
            null
        }
    }
}
