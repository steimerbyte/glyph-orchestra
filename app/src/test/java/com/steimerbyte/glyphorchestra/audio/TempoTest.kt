package com.steimerbyte.glyphorchestra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TempoTest {

    @Test
    fun `120 bpm klickzug trifft tempo und raster`() {
        val envelope = ClickTrack.analyse(ClickTrack.track(120.0, 8.0))
        val result = TempoEstimator().estimate(envelope)

        assertEquals(120.0, result.bpm, 1.0)
        assertTrue("Beats: ${result.beats.size}", result.beats.size in 14..18)

        // Jeder Klick muss einen Beat innerhalb von 20 ms haben. Der
        // Vorlauf des 46-ms-Fensters steckt in diesem Fenster.
        for (k in 0 until 16) {
            val expected = 0.3 + k * 0.5
            val nearest = result.beats.minByOrNull { abs(it - expected) } ?: break
            assertTrue(
                "Klick $k bei $expected, nächster Beat $nearest",
                abs(nearest - expected) <= 0.020,
            )
        }
    }

    @Test
    fun `90 und 180 bpm sind unterscheidbar`() {
        val at90 = TempoEstimator().estimate(ClickTrack.analyse(ClickTrack.track(90.0, 8.0)))
        val at180 = TempoEstimator().estimate(ClickTrack.analyse(ClickTrack.track(180.0, 8.0)))

        assertEquals(90.0, at90.bpm, 1.0)
        assertEquals(180.0, at180.bpm, 1.0)
        assertTrue("90 und 180 liegen zu nah beieinander", abs(at90.bpm - at180.bpm) > 50.0)
    }

    @Test
    fun `ein walzer wird als drei viertel erkannt`() {
        // Zwölf Sekunden sind bei 120 BPM acht volle Takte: der Kick liegt
        // auf der Eins, Becken auf zwei und drei.
        val envelope = ClickTrack.analyse(ClickTrack.track(120.0, 12.0, accentEvery = 3))
        val result = TempoEstimator().estimate(envelope)

        assertEquals(120.0, result.bpm, 1.0)
        assertEquals(3, result.meter)
        assertTrue("Konfidenz ${result.meterConfidence}", result.meterConfidence >= 0.6)
        assertEquals("3/4", result.meterLabel())
    }

    @Test
    fun `ein driftendes tempo wird verfolgt`() {
        // 116 BPM, je Klick 0,5 schneller: die IBI-Streuung liegt über 2 %,
        // also läuft der Zwei-Pass mit lokaler Periode.
        val envelope = ClickTrack.analyse(ClickTrack.ramp(116.0, 0.5, 16.0))
        val result = TempoEstimator().estimate(envelope)

        assertTrue("Beats: ${result.beats.size}", result.beats.size in 20..40)
        assertTrue("Konfidenz ${result.confidence}", result.confidence > 0.3)
        val first = result.beats[2] - result.beats[1]
        val last = result.beats.last() - result.beats[result.beats.size - 2]
        assertTrue("IBI ist nicht geschrumpft: $first -> $last", last < first)
    }

    @Test
    fun `stille liefert keine beats und keine konfidenz`() {
        val result = TempoEstimator().estimate(ClickTrack.analyse(ClickTrack.silence(8.0)))

        assertEquals(0, result.beats.size)
        assertTrue("Konfidenz ${result.confidence}", result.confidence < 0.3)
        assertEquals("?", result.meterLabel())
    }

    @Test
    fun `konstantes rauschen liefert keine beats und keine konfidenz`() {
        val result = TempoEstimator().estimate(
            ClickTrack.analyse(ClickTrack.noise(8.0, seed = 4711L)),
        )

        assertEquals(0, result.beats.size)
        assertTrue("Konfidenz ${result.confidence}", result.confidence < 0.3)
    }

    @Test
    fun `das tempo bleibt bei acht sekunden fenster stabil`() {
        // 8-s-Fenster haben bei tau = 86 Frames 12,5 % ACF-Bias; die
        // erwartungstreue Division muss das auffangen, sonst läge die
        // Schätzung bei 67,5 statt 60 BPM.
        val result = TempoEstimator().estimate(ClickTrack.analyse(ClickTrack.track(60.0, 8.0)))

        assertEquals(60.0, result.bpm, 1.0)
    }
}
