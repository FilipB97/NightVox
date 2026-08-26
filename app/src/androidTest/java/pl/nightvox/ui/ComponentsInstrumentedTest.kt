package pl.nightvox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.nightvox.ui.components.EmptyState
import pl.nightvox.ui.components.LiveLevelMeter
import pl.nightvox.ui.components.NightCard
import pl.nightvox.ui.components.ScreenHeader
import pl.nightvox.ui.components.StatTile
import pl.nightvox.ui.components.WaveformView
import pl.nightvox.ui.theme.NightVoxTheme

/**
 * Smoke test wspólnych komponentów.
 *
 * Rysowanie na `Canvas` to jedyne miejsce w tym UI, gdzie da się wywalić aplikację arytmetyką:
 * pusta historia poziomu, jedna próbka, zerowa szerokość kubełka. Kompilator tego nie złapie,
 * a użytkownik zobaczy to jako crash ekranu głównego w środku nocy.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun karta_naglowek_i_kafelek_pokazuja_tresc() {
        compose.setContent {
            NightVoxTheme {
                Column {
                    ScreenHeader(title = "Klipy", subtitle = "3 nagrania")
                    NightCard {
                        StatTile("klipy", "12")
                    }
                }
            }
        }
        compose.onNodeWithText("Klipy").assertIsDisplayed()
        compose.onNodeWithText("3 nagrania").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        // StatTile podaje etykietę wersalikami — sprawdzamy to, co naprawdę widzi użytkownik.
        compose.onNodeWithText("KLIPY").assertIsDisplayed()
    }

    @Test
    fun pusty_stan_pokazuje_powod() {
        compose.setContent { NightVoxTheme { EmptyState("Brak klipów.") } }
        compose.onNodeWithText("Brak klipów.").assertIsDisplayed()
    }

    /** Historia pusta i jednoelementowa — dwa przypadki, w których łatwo podzielić przez zero. */
    @Test
    fun miernik_nie_wywala_sie_na_brzegowej_historii() {
        compose.setContent {
            NightVoxTheme {
                Column {
                    LiveLevelMeter(
                        history = emptyList(),
                        levelDb = -90f,
                        floorDb = -78f,
                        thresholdDb = -50f,
                        isRecording = false,
                    )
                    LiveLevelMeter(
                        history = listOf(-60f),
                        levelDb = -60f,
                        floorDb = -78f,
                        thresholdDb = -50f,
                        isRecording = true,
                        capacity = 1,
                    )
                    LiveLevelMeter(
                        history = List(200) { -70f + it % 30 },
                        levelDb = -41f,
                        floorDb = -78f,
                        thresholdDb = -50f,
                        isRecording = false,
                        capacity = 120,
                    )
                }
            }
        }
        compose.onNodeWithText("PRÓG").assertIsDisplayed()
    }

    /** Klip bez pliku `.peaks` i klip z jednym kubełkiem. */
    @Test
    fun obwiednia_nie_wywala_sie_bez_danych() {
        compose.setContent {
            NightVoxTheme {
                Column {
                    WaveformView(peaks = null, progress = 0f)
                    WaveformView(peaks = ByteArray(0), progress = 0.5f)
                    WaveformView(peaks = byteArrayOf(120), progress = 1f)
                    WaveformView(peaks = ByteArray(500) { (it % 255).toByte() }, progress = 0.3f)
                }
            }
        }
        compose.waitForIdle()
    }
}
