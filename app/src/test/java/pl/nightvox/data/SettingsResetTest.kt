package pl.nightvox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Przywróć domyślne" dotyczy parametrów bramki, nie tego, czy powitanie już było.
 *
 * Gdyby resetowało też tę flagę, użytkownik po zmianie jednego suwaka i cofnięciu zmian
 * lądowałby z powrotem na ekranie powitalnym — co wygląda jak utrata danych.
 */
class SettingsResetTest {

    @Test
    fun reset_przywraca_parametry_ale_nie_cofa_powitania() {
        val current = NightVoxSettings(
            triggerDeltaDb = 20f,
            minTriggerDb = -62f,
            speechFilterThreshold = 0.75f,
            onboardingCompleted = true,
        )
        val reset = NightVoxSettings.DEFAULTS.copy(onboardingCompleted = current.onboardingCompleted)

        assertEquals(NightVoxSettings.DEFAULTS.triggerDeltaDb, reset.triggerDeltaDb)
        assertEquals(NightVoxSettings.DEFAULTS.minTriggerDb, reset.minTriggerDb)
        assertEquals(NightVoxSettings.DEFAULTS.speechFilterThreshold, reset.speechFilterThreshold)
        assertTrue("reset cofnął użytkownika na powitanie", reset.onboardingCompleted)
    }

    @Test
    fun swieza_instalacja_zaczyna_od_powitania() {
        assertEquals(false, NightVoxSettings.DEFAULTS.onboardingCompleted)
    }
}
