package pl.nightvox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.nightvox.audio.SignalFixtures.FRAME
import pl.nightvox.audio.SignalFixtures.SAMPLE_RATE
import pl.nightvox.audio.SignalFixtures.mixInto
import pl.nightvox.audio.SignalFixtures.msToSamples
import pl.nightvox.audio.SignalFixtures.noise
import pl.nightvox.audio.SignalFixtures.tone
import kotlin.math.abs

/**
 * Kryteria akceptacji fazy 1 (§9 planu) plus przypadki z §10.
 *
 * To jest najważniejszy plik testowy w projekcie: jeśli bramka gubi pierwszą sylabę albo
 * rozsypuje jedną wypowiedź na pięć klipów, cała apka jest bezużyteczna.
 */
class GateTest {

    private val floorDb = -60f
    private val speechDb = -32f

    private fun config(
        warmupMs: Long = 2_000,
        triggerDeltaDb: Float = 12f,
        attackFrames: Int = 3,
        preRollMs: Long = 3_000,
        hangoverMs: Long = 4_000,
        mergeGapMs: Long = 2_000,
        minVoicedMs: Long = 400,
        maxClipMs: Long = 120_000,
    ) = GateConfig(
        warmupMs = warmupMs,
        triggerDeltaDb = triggerDeltaDb,
        attackFrames = attackFrames,
        preRollMs = preRollMs,
        hangoverMs = hangoverMs,
        mergeGapMs = mergeGapMs,
        minVoicedMs = minVoicedMs,
        maxClipMs = maxClipMs,
    )

    @Test
    fun `cicha noc nie produkuje klipow`() {
        val harness = GateHarness(config())
        harness.feed(noise(msToSamples(60_000), floorDb))
        harness.finish()

        assertEquals(0, harness.clips.size)
        assertEquals(0, harness.discarded.size)
    }

    /** Kryterium akceptacji fazy 1: 60 s ciszy, jeden 2-sekundowy burst → dokładnie 1 klip. */
    @Test
    fun `pojedynczy burst w ciszy daje dokladnie jeden klip`() {
        val cfg = config()
        val signal = noise(msToSamples(60_000), floorDb)
        val burstAt = msToSamples(30_000)
        val burstLength = msToSamples(2_000)
        mixInto(signal, tone(burstLength, speechDb), burstAt)

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals(0, harness.discarded.size)
        assertEquals(1, harness.clips.size)

        val clip = harness.clips.single()
        val expectedMs = cfg.preRollMs + 2_000 + cfg.hangoverMs
        assertTrue(
            "Długość klipu ${clip.stats.durationMs} ms poza oczekiwaniem ~$expectedMs ms",
            abs(clip.stats.durationMs - expectedMs) <= 300,
        )
    }

    /** Bez tego cała apka jest bezużyteczna: pierwsza sylaba musi być w pliku. */
    @Test
    fun `pre-roll zawiera audio sprzed triggera bit w bit`() {
        val cfg = config()
        val signal = noise(msToSamples(30_000), floorDb)
        val burstAt = msToSamples(15_000)
        mixInto(signal, tone(msToSamples(2_000), speechDb), burstAt)

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        val clip = harness.clips.single()
        val offset = harness.sampleOffsetOf(clip)

        assertTrue("Klip zaczyna się po starcie bursta — pierwsza sylaba przepadła", offset < burstAt)

        // Okno pre-rollu jest zakotwiczone w momencie triggera, a trigger pada dopiero po
        // `attackFrames` ramkach powyżej progu — te ramki siedzą już wewnątrz okna. Realnej
        // ciszy sprzed wypowiedzi jest więc dokładnie preRoll − attackFrames·frame.
        val preRollSamples = burstAt - offset
        val expected = cfg.preRollSamples - cfg.attackFrames * FRAME
        assertEquals(
            "Pre-roll ma $preRollSamples próbek zamiast $expected",
            expected.toLong(),
            preRollSamples.toLong(),
        )

        // Zawartość klipu musi być dokładnie tym wycinkiem sygnału — nie „jakimś" audio
        // odpowiedniej długości.
        for (i in clip.samples.indices) {
            val source = offset + i
            if (source >= signal.size) break
            assertEquals("Różnica na próbce $i (offset $source)", signal[source], clip.samples[i])
        }
    }

    @Test
    fun `dwa bursty blizej niz mergeGap trafiaja do jednego klipu`() {
        val cfg = config(hangoverMs = 1_000, mergeGapMs = 2_000)
        val signal = noise(msToSamples(40_000), floorDb)
        mixInto(signal, tone(msToSamples(1_000), speechDb), msToSamples(10_000))
        // Druga wypowiedź startuje 2 s po końcu pierwszej: hangover (1 s) + okno scalania (2 s).
        mixInto(signal, tone(msToSamples(1_000), speechDb), msToSamples(13_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals("Jedna wypowiedź z pauzą rozsypała się na klipy", 1, harness.clips.size)
        assertEquals(2, harness.clips.single().stats.segments)
    }

    @Test
    fun `dwa bursty dalej niz mergeGap daja dwa klipy`() {
        val cfg = config(hangoverMs = 1_000, mergeGapMs = 2_000)
        val signal = noise(msToSamples(40_000), floorDb)
        mixInto(signal, tone(msToSamples(1_000), speechDb), msToSamples(10_000))
        mixInto(signal, tone(msToSamples(1_000), speechDb), msToSamples(20_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals(2, harness.clips.size)
        assertTrue(harness.clips.all { it.stats.segments == 1 })
    }

    @Test
    fun `narastajacy szum nie wyzwala bramki`() {
        val cfg = config()
        val durationMs = 120_000L
        val total = msToSamples(durationMs)
        val signal = ShortArray(total)
        // Tło rośnie liniowo z -70 do -45 dBFS przez dwie minuty (klimatyzacja, deszcz).
        val chunk = FRAME
        var at = 0
        var seed = 1
        while (at + chunk <= total) {
            val progress = at.toFloat() / total
            val level = -70f + 25f * progress
            System.arraycopy(noise(chunk, level, seed++), 0, signal, at, chunk)
            at += chunk
        }

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals("Tło nie nadążyło i bramka strzeliła fałszywie", 0, harness.clips.size)
    }

    @Test
    fun `krotki trzask jest odrzucany przez minVoicedMs`() {
        // attackFrames = 1, żeby trzask w ogóle wyzwolił bramkę — sprawdzamy filtr długości,
        // nie filtr ataku.
        val cfg = config(attackFrames = 1, minVoicedMs = 400)
        val signal = noise(msToSamples(20_000), floorDb)
        mixInto(signal, tone(msToSamples(100), -20f), msToSamples(10_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals("Trzask nie powinien dać klipu", 0, harness.clips.size)
        assertEquals(1, harness.discarded.size)
        assertEquals(DiscardReason.TOO_SHORT, harness.discarded.single().first)
    }

    @Test
    fun `trzask 30 ms nie przechodzi nawet przez attackFrames`() {
        val cfg = config()
        val signal = noise(msToSamples(20_000), floorDb)
        mixInto(signal, tone(msToSamples(30), -20f), msToSamples(10_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertEquals(0, harness.clips.size)
        assertEquals(0, harness.discarded.size)
    }

    @Test
    fun `ciagly halas jest ciety na maxClipMs`() {
        val cfg = config(maxClipMs = 10_000, hangoverMs = 1_000, mergeGapMs = 500)
        val signal = noise(msToSamples(60_000), floorDb)
        mixInto(signal, tone(msToSamples(45_000), speechDb), msToSamples(5_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        assertTrue("Oczekiwano kilku klipów po cięciu, było ${harness.clips.size}", harness.clips.size >= 4)
        harness.clips.forEach {
            assertTrue(
                "Klip ${it.stats.durationMs} ms przekroczył maxClipMs",
                it.stats.durationMs <= cfg.maxClipMs + cfg.frameMs,
            )
        }
    }

    @Test
    fun `flush domyka klip otwarty w momencie konca sesji`() {
        val cfg = config()
        val signal = noise(msToSamples(20_000), floorDb)
        // Burst do samego końca sygnału — bramka jest w RECORDING, gdy sesja się kończy.
        mixInto(signal, tone(msToSamples(3_000), speechDb), msToSamples(17_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        assertEquals("Klip nie powinien być jeszcze zamknięty", 0, harness.clips.size)

        harness.finish()
        assertEquals(1, harness.clips.size)
    }

    /**
     * Odliczanie warm-upu idzie po przetworzonych ramkach, nie po zegarku. Gdyby liczyło
     * czas od startu sesji, przerwanie mikrofonu w trakcie pomiaru tła pokazywałoby zero
     * mimo że tło wciąż nie jest zmierzone.
     */
    @Test
    fun `odliczanie warm-upu idzie za ramkami a nie zegarem`() {
        val cfg = config(warmupMs = 2_000)
        val harness = GateHarness(cfg)

        assertEquals(2_000L, harness.gate.warmupRemainingMs)

        harness.feed(noise(msToSamples(1_000), floorDb))
        assertEquals(
            "Po sekundzie ramek powinna zostać sekunda warm-upu",
            1_000L,
            harness.gate.warmupRemainingMs,
        )
        assertTrue(harness.gate.isWarmingUp)

        harness.feed(noise(msToSamples(1_000), floorDb))
        assertEquals(0L, harness.gate.warmupRemainingMs)
        assertEquals(GateState.IDLE, harness.gate.state)
    }

    @Test
    fun `statystyki klipu odpowiadaja zawartosci`() {
        val cfg = config()
        val signal = noise(msToSamples(30_000), floorDb)
        mixInto(signal, tone(msToSamples(2_000), speechDb), msToSamples(15_000))

        val harness = GateHarness(cfg)
        harness.feed(signal)
        harness.finish()

        val clip = harness.clips.single()
        assertEquals(
            "durationMs nie zgadza się z liczbą zapisanych próbek",
            clip.samples.size.toLong() * 1000 / SAMPLE_RATE,
            clip.stats.durationMs,
        )
        assertTrue("voicedMs=${clip.stats.voicedMs} — mowa trwała 2 s", clip.stats.voicedMs >= 1_500)
        assertTrue("peakDb=${clip.stats.peakDb} powinno być bliskie -29 dBFS", clip.stats.peakDb > -32f)
        assertTrue(clip.stats.meanDb < clip.stats.peakDb)
    }
}
