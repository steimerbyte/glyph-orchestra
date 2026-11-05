package com.steimerbyte.glyphorchestra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Die Merkmale müssen auf einem synthetischen Spektrum genau die Werte
 * liefern, die der Bericht nennt -- sonst verschiebt sich später die ganze
 * Spannungskette, ohne dass es am Ende einer Ursache zuzuordnen wäre.
 */
class FeaturesTest {

    private val binHz = 44100.0 / 2048.0

    /** Gauss förmiger Ausschlag um [hz]; Breite 40 Hz. */
    private fun tone(hz: Double, peak: Float = 1f): FloatArray {
        val power = FloatArray(1025)
        for (k in Features.BIN_FROM..Features.BIN_TO) {
            val d = (k * binHz - hz) / 40.0
            power[k] = (peak * exp(-0.5 * d * d)).toFloat()
        }
        return power
    }

    @Test
    fun `der schwerpunkt eines 1-khz-sinus liegt bei 1 khz`() {
        val c = Features.centroid(tone(1000.0), binHz)
        assertEquals(1000.0, c, 20.0)
    }

    @Test
    fun `ein einzelner bin liefert exakt seine frequenz`() {
        val power = FloatArray(1025)
        power[46] = 1f // 46 x 21,533 Hz = 990,5 Hz
        assertEquals(46 * binHz, Features.centroid(power, binHz), 1e-9)
    }

    @Test
    fun `ein leeres spektrum ergibt null statt nan`() {
        val power = FloatArray(1025)
        assertEquals(0.0, Features.centroid(power, binHz), 0.0)
        assertEquals(0.0, Features.spread(power, binHz, 0.0), 0.0)
        assertEquals(0.0, Features.rolloff85(power, binHz), 0.0)
        assertEquals(0.0, Features.flatness(power), 0.0)
    }

    @Test
    fun `bandbreite eines schmalen tons ist klein`() {
        val power = tone(1000.0)
        val spread = Features.spread(power, binHz, Features.centroid(power, binHz))
        assertTrue("Bandbreite war $spread", spread < 100.0)
    }

    @Test
    fun `rolloff 85 prozent eines flachen spektrums liegt bei 85 prozent der bins`() {
        val power = FloatArray(1025) { 1f }
        val expected = 436 * binHz // 5 + 0,85 x 508 = 436,8
        assertEquals(expected, Features.rolloff85(power, binHz), 30.0)
    }

    @Test
    fun `weißes rauschen hat flatness nahe eins`() {
        val random = Random(4711)
        val power = FloatArray(1025)
        for (k in Features.BIN_FROM..Features.BIN_TO) {
            // Flaches Spektrum mit +-10 % Welligkeit: eine einzelne
            // Rauschrealisierung schwankt viel stärker und liegt bei ~0,56,
            // erst über viele Frames nähert sie sich der 1.
            power[k] = 1f + 0.1f * (random.nextFloat() * 2f - 1f)
        }
        val flatness = Features.flatness(power)
        assertTrue("Flatness war $flatness", flatness > 0.9)
    }

    @Test
    fun `ein sinus hat flatness nahe null`() {
        val power = FloatArray(1025)
        power[46] = 1f
        val flatness = Features.flatness(power)
        assertTrue("Flatness war $flatness", flatness < 0.01)
    }

    @Test
    fun `zcr eines 1-khz-sinus ist 1 khz`() {
        val samples = FloatArray(2048) { sin(2.0 * PI * 1000.0 * it / 44100.0).toFloat() }
        assertEquals(1000.0, Features.zcr(44100, samples), 15.0)
    }

    @Test
    fun `rms eines sinus mit amplitude 0,5 ist 0,3536`() {
        val samples = FloatArray(2048) { (0.5 * sin(2.0 * PI * 440.0 * it / 44100.0)).toFloat() }
        assertEquals(0.35355, Features.rms(samples), 0.001)
        // -9,03 dB plus 4,26 dB Hann-Korrektur.
        assertEquals(-4.77, Features.rmsDb(samples), 0.05)
    }
}
