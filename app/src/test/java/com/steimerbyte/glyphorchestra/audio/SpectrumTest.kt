package com.steimerbyte.glyphorchestra.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Bänder und die Pegelnormierung, an Signalen nachgerechnet.
 *
 * Alle Frequenzen sind hier exakt bekannt, damit ein verschobenes Band oder
 * eine falsche Hann-Normierung auffällt und nicht als "klingt plausibel"
 * durchgeht.
 */
class SpectrumTest {

    private val fs = 44100

    private fun sine(spec: Spectrum, freq: Double, amp: Float = 1f): FloatArray =
        FloatArray(spec.n) { (amp * sin(2.0 * PI * freq * it / fs)).toFloat() }

    /** Bin-genau, damit die Leistung nicht zwischen zwei Bins verteilt ist. */
    private fun cosineAtBin(spec: Spectrum, k: Int, amp: Float = 1f): FloatArray =
        FloatArray(spec.n) { (amp * cos(2.0 * PI * k * it / spec.n)).toFloat() }

    /** Band mit dem höchsten Pegel; bei Gleichstand gewinnt das tiefere. */
    private fun topBand(spec: Spectrum, count: Int = Spectrum.BAND_COUNT): Int {
        var best = 0
        for (b in 1 until count) {
            if (spec.bandsDb[b] > spec.bandsDb[best]) best = b
        }
        return best
    }

    @Test
    fun `die Fensterlaenge folgt der Abtastrate`() {
        assertEquals(2048, Spectrum(44100).n)
        assertEquals(2048, Spectrum(48000).n)
        assertEquals(1024, Spectrum(22050).n)
        assertEquals(4096, Spectrum(96000).n)
    }

    @Test
    fun `ein 1 kHz Sinus landet in Band 8 plus minus 1`() {
        val spec = Spectrum(fs)
        spec.analyze(sine(spec, 1000.0))
        val band = topBand(spec, Dsp.BANDS)
        assertTrue("1000 Hz landete in Band $band, erwartet 8 ±1", abs(band - 8) <= 1)
    }

    @Test
    fun `jede Bandmitte landet in ihrem eigenen Band`() {
        val spec = Spectrum(fs)
        for (b in 0 until Dsp.BANDS) {
            val mitte = sqrt(spec.bandLowHz[b] * spec.bandHighHz[b])
            spec.analyze(sine(spec, mitte))
            assertEquals("Band $b bei ${mitte.toInt()} Hz", b, topBand(spec, Dsp.BANDS))
        }
    }

    @Test
    fun `ein 60 Hz Sinus liegt im Tieftonband`() {
        val spec = Spectrum(fs)
        spec.analyze(sine(spec, 60.0))
        assertEquals(Spectrum.LOW_BAND, topBand(spec))
    }

    @Test
    fun `Stille liefert den dB-Floor`() {
        val spec = Spectrum(fs)
        spec.analyze(FloatArray(spec.n))
        for (b in 0 until Spectrum.BAND_COUNT) {
            assertEquals("Band $b", -120.0, spec.bandsDb[b].toDouble(), 0.01)
        }
        for (p in spec.power) assertEquals(0.0, p.toDouble(), 0.0)
    }

    @Test
    fun `ein Vollaussteuerungssinus hat im Peak-Bin die Leistung 1`() {
        val spec = Spectrum(fs)
        spec.analyze(cosineAtBin(spec, 100))
        var peak = 0f
        for (p in spec.power) if (p > peak) peak = p
        // Hann-Normierung 16/n²: der Sinus selbst darf nicht 2048fach zählen.
        assertEquals(1.0, peak.toDouble(), 1e-3)
    }

    @Test
    fun `die Bandgrenzen steigen monoton`() {
        val spec = Spectrum(fs)
        assertEquals(100.0, spec.bandLowHz[0], 1e-9)
        assertEquals(11025.0, spec.bandHighHz[Dsp.BANDS - 1], 1e-6)
        assertEquals(Dsp.LOW_BAND_HZ_FROM, spec.bandLowHz[Spectrum.LOW_BAND], 1e-9)
        assertEquals(Dsp.LOW_BAND_HZ_TO, spec.bandHighHz[Spectrum.LOW_BAND], 1e-9)
        for (b in 1 until Dsp.BANDS) {
            assertTrue(
                "Band $b beginnt bei ${spec.bandLowHz[b]}, Band ${b - 1} erst bei ${spec.bandLowHz[b - 1]}",
                spec.bandLowHz[b] > spec.bandLowHz[b - 1],
            )
            assertTrue(
                "Band $b endet bei ${spec.bandHighHz[b]}",
                spec.bandHighHz[b] > spec.bandHighHz[b - 1],
            )
        }
    }

    @Test
    fun `die Pegel folgen der Amplitude`() {
        val spec = Spectrum(fs)
        spec.analyze(sine(spec, 1000.0))
        val laut = topBand(spec, Dsp.BANDS)
        val lautDb = spec.bandsDb[laut]

        // Halbe Amplitude sind -6,02 dB, unabhängig von der Bandnormierung.
        spec.analyze(sine(spec, 1000.0, 0.5f))
        assertEquals(laut, topBand(spec, Dsp.BANDS))
        assertEquals(lautDb - 6.0206, spec.bandsDb[laut].toDouble(), 0.05)
    }
}
