package pl.nightvox.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Krótkie odczucia w dłoni na akcjach, które coś zmieniają.
 *
 * Compose ma własne `HapticFeedback`, ale zna tylko dwa rodzaje wibracji. Stałe z `View` dają
 * właściwy słownik: potwierdzenie brzmi inaczej niż odmowa, a przeskok suwaka to cichy tik.
 * Wszystkie przechodzą przez systemowe ustawienie haptyki, więc kto ją wyłączył, nic nie
 * poczuje.
 */
@Immutable
class Haptics(private val view: View) {

    /** Przeskok wartości: suwak, chip, przełącznik. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Coś się właśnie stało i to było zamierzone: start sesji, przywrócenie klipu. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        },
    )

    /** Koniec czynności, która trwała: zatrzymanie sesji. */
    fun end() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        },
    )

    /** Coś nieodwracalnego albo odmowa. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
