package com.steimerbyte.glyphorchestra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spannung und Beat-Raster. Die Beispielwerte aus dem Bericht stehen hier
 * fest, damit die Gewichte nicht unbemerkt verrutschen; der Rest prüft die
 * Eigenschaften, auf die es im Abspielpfad ankommt: 0..1, schneller Anstieg,
 * langsamer Abfall und keine Treppe auf dem Raster.
 */
class TensionTest {

    private val ranges = TensionRanges(
        loudP95Db = -6f,
        centroidP5Log2 = 9f,     // 512 Hz
        centroidP95Log2 = 12f,   // 4096 Hz
        roughnessP5 = 0f,
        roughnessP95 = 0.05f,
        noveltyP5 = 0f,
        noveltyP95 = 0.3f,
    )

    private val dt = 1.0 / 86.1328125

    private fun settled(
        tension: Tension,
        loudDb: Float,
        centroidHz: Float,
        roughness: Float,
        novelty: Float,
        frames: Int = 200,
    ): Float {
        repeat(frames) { tension.push(loudDb, centroidHz, roughness, novelty, dt) }
        return tension.value
    }

    @Test
    fun `die gewichtete summe trifft die beispielwerte des berichts`() {
        assertEquals(0.267, Tension.raw(0.3, 0.4, 0.2, 0.1), 1e-9)
        assertEquals(0.771, Tension.raw(0.9, 0.7, 0.4, 0.8), 1e-9)
        assertEquals(0.495, Tension.raw(0.6, 0.6, 0.3, 0.3), 1e-9)
    }

    @Test
    fun `die spannung bleibt zwischen null und eins`() {
        val loud = Tension(ranges)
        settled(loud, loudDb = 120f, centroidHz = 1e9f, roughness = 1e9f, novelty = 1e9f)
        assertTrue("oben: ${loud.value}", loud.value in 0f..1f)

        val quiet = Tension(ranges)
        settled(quiet, loudDb = -1e9f, centroidHz = 0f, roughness = -1e9f, novelty = -1e9f)
        assertTrue("unten: ${quiet.value}", quiet.value in 0f..1f)
    }

    @Test
    fun `lauteres und dichteres material ergibt mehr spannung`() {
        val quiet = settled(Tension(ranges), -40f, 600f, 0f, 0f)
        val loud = settled(Tension(ranges), -8f, 5000f, 0.1f, 0.5f)
        assertTrue("leise $quiet, laut $loud", loud > quiet + 0.4)
    }

    @Test
    fun `der anstieg ist schneller als der abfall`() {
        val tension = Tension(ranges)
        // Ein Frame mit Ziel 1: alpha_up = exp(-0,1/0,1) = 0,368.
        tension.push(0f, 5000f, 0.1f, 1f, 0.1)
        val rise = tension.value
        // Ein Frame mit Ziel 0: alpha_dn = exp(-0,1/0,4) = 0,779.
        tension.push(-60f, 100f, 0f, 0f, 0.1)
        val drop = rise - tension.value
        assertTrue("Anstieg $rise, Abfall $drop", rise > drop * 2f)
    }

    @Test
    fun `ohne beats wird nur auf die anzeigerate umgesetzt`() {
        val curve = FloatArray(100) { 0.7f }
        val out = FloatArray(60)
        Tension.quantize(curve, 100.0, DoubleArray(0), 30, 60, out)
        for (value in out) assertEquals(0.7f, value, 1e-6f)
    }

    @Test
    fun `das raster interpoliert zwischen den viertelbeats`() {
        // Sprung bei t = 0,6 s, Raster bei 0/0,25/0,5/0,75/1,0 s.
        val curve = FloatArray(200) { if (it < 60) 0f else 1f }
        val beats = doubleArrayOf(0.0, 0.5, 1.0, 1.5)
        val out = FloatArray(120)
        Tension.quantize(curve, 100.0, beats, 60, 120, out)

        assertEquals(0f, out[30], 1e-6f)              // t = 0,5 s, Rasterpunkt
        assertEquals(1f, out[45], 1e-6f)              // t = 0,75 s, Rasterpunkt
        assertEquals(0.4667f, out[37], 0.01f)         // t = 0,617 s, dazwischen
        assertEquals(1f, out[119], 1e-6f)             // nach dem letzten Beat
    }

    @Test
    fun `ein konstantes signal bleibt auch auf dem raster konstant`() {
        val curve = FloatArray(200) { 0.3f }
        val beats = doubleArrayOf(0.0, 0.5, 1.0)
        val out = FloatArray(90)
        Tension.quantize(curve, 100.0, beats, 30, 90, out)
        for (value in out) assertEquals(0.3f, value, 1e-6f)
    }
}
