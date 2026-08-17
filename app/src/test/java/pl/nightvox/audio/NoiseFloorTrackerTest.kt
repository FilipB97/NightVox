package pl.nightvox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoiseFloorTrackerTest {

    @Test
    fun `warm-up bierze mediane a nie srednia`() {
        val tracker = NoiseFloorTracker(warmupFrames = 5)
        // Trzask ładowarki na starcie sesji nie może zatruć progu na całą noc.
        listOf(-60f, -61f, -59f, -60f, -5f).forEach { tracker.update(it) }

        assertTrue(tracker.isWarmedUp)
        assertEquals(-60f, tracker.floorDb, 0.01f)
    }

    @Test
    fun `warmupRemaining maleje az do zera`() {
        val tracker = NoiseFloorTracker(warmupFrames = 3)
        assertEquals(3, tracker.warmupRemaining)
        tracker.update(-60f)
        assertEquals(2, tracker.warmupRemaining)
        tracker.update(-60f)
        tracker.update(-60f)
        assertEquals(0, tracker.warmupRemaining)
        assertTrue(tracker.isWarmedUp)
    }

    @Test
    fun `spadek tla jest szybki a wzrost wolny`() {
        val fall = NoiseFloorTracker(warmupFrames = 1).apply { update(-50f) }
        val rise = NoiseFloorTracker(warmupFrames = 1).apply { update(-50f) }

        repeat(50) { fall.update(-70f) }
        repeat(50) { rise.update(-30f) }

        val fallen = abs(fall.floorDb - (-50f))
        val risen = abs(rise.floorDb - (-50f))
        assertTrue(
            "Spadek ($fallen dB) powinien być wyraźnie szybszy niż wzrost ($risen dB)",
            fallen > risen * 5,
        )
    }

    /**
     * Kluczowa własność z §10: długa wypowiedź nie może podnieść tła. W praktyce zapewnia
     * to bramka, która woła [NoiseFloorTracker.update] tylko w stanie IDLE — tutaj
     * sprawdzamy, że tak faktycznie jest, na pełnym pipeline.
     */
    @Test
    fun `tlo nie rosnie podczas dlugiej wypowiedzi`() {
        val config = GateConfig(warmupMs = 2_000, hangoverMs = 1_000, mergeGapMs = 500)
        val harness = GateHarness(config)

        harness.feed(SignalFixtures.noise(SignalFixtures.msToSamples(10_000), -60f))
        val floorBefore = harness.gate.floorDb

        // 30 sekund nieprzerwanego mówienia.
        harness.feed(SignalFixtures.tone(SignalFixtures.msToSamples(30_000), -30f))
        val floorDuring = harness.gate.floorDb

        assertTrue(
            "Tło skoczyło z $floorBefore na $floorDuring — bramka sama by się wyciszyła",
            floorDuring - floorBefore < 1f,
        )
    }

    @Test
    fun `finishWarmup przed czasem ustala tlo z zebranych probek`() {
        val tracker = NoiseFloorTracker(warmupFrames = 100)
        listOf(-55f, -57f, -56f).forEach { tracker.update(it) }
        assertFalse(tracker.isWarmedUp)

        tracker.finishWarmup()
        assertTrue(tracker.isWarmedUp)
        assertEquals(-56f, tracker.floorDb, 0.01f)
    }
}
