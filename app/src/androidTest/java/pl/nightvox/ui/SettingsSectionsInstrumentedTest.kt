package pl.nightvox.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.nightvox.ui.settings.SectionControl
import pl.nightvox.ui.settings.SettingsSection
import pl.nightvox.ui.theme.NightVoxTheme

/**
 * Harmonijka w Ustawieniach: zwinięta sekcja pokazuje bieżące wartości i nie pokazuje swojej
 * zawartości.
 *
 * To jest cała treść przebudowy tego ekranu — trzynaście suwaków rozwiniętych naraz było nie
 * do przejrzenia. Test pilnuje obu połówek: że domyślnie jest zwinięte i że kliknięcie
 * naprawdę otwiera.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSectionsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun zwinieta_sekcja_pokazuje_podsumowanie_zamiast_zawartosci() {
        compose.setContent {
            NightVoxTheme {
                var expanded by remember { mutableStateOf(false) }
                SettingsSection(
                    title = "Czułość",
                    summary = "12 dB nad tłem",
                    control = SectionControl(expanded = expanded, onToggle = { expanded = !expanded }),
                ) {
                    Text("suwak progu")
                }
            }
        }

        compose.onNodeWithText("Czułość").assertIsDisplayed()
        compose.onNodeWithText("12 dB nad tłem").assertIsDisplayed()
        compose.onNodeWithText("suwak progu").assertDoesNotExist()
    }

    @Test
    fun klikniecie_naglowka_otwiera_sekcje() {
        compose.setContent {
            NightVoxTheme {
                var expanded by remember { mutableStateOf(false) }
                SettingsSection(
                    title = "Czułość",
                    summary = "12 dB nad tłem",
                    control = SectionControl(expanded = expanded, onToggle = { expanded = !expanded }),
                ) {
                    Text("suwak progu")
                }
            }
        }

        compose.onNodeWithText("Czułość").performClick()
        compose.onNodeWithText("suwak progu").assertIsDisplayed()
        // Po otwarciu podsumowanie znika — te same liczby są już przy suwakach.
        compose.onNodeWithText("12 dB nad tłem").assertDoesNotExist()
    }
}
