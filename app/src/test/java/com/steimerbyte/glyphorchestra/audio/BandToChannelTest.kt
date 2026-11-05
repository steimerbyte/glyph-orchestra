package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.glyph.Level
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Zuordnung Band -> Kanal ist die Stelle, an der ein stiller Versatz
 * später die falsche LED anspricht. Geprüft wird deshalb die Ringordnung
 * selbst, das Verhalten bei Stille und die Glättung zwischen zwei Bändern.
 */
class BandToChannelTest {

    private val spec = DeviceCatalog.PHONE_3A

    private fun silentBands(): FloatArray = FloatArray(BandToChannel.BAND_COUNT) { -100f }

    private fun brightBands(band: Int, db: Float = 0f): FloatArray =
        silentBands().also { it[band] = db }

    private fun levelsOf(bands: FloatArray, tension: Float = 0f, mapper: BandToChannel = BandToChannel(spec)): IntArray {
        val levels = IntArray(36)
        mapper.map(bands, tension, levels)
        return levels
    }

    @Test
    fun `die ringreihenfolge beginnt an der naht bei 148 grad`() {
        val expected = IntArray(36)
        for (i in 0 until 5) expected[i] = 31 + i      // B1..B5, unten
        for (i in 0 until 20) expected[5 + i] = i      // C1..C20, links und oben
        for (i in 0 until 11) expected[25 + i] = 20 + i // A1..A11, rechts
        assertArrayEquals(expected, BandToChannel(spec).ring)
    }

    @Test
    fun `die mittenfrequenzen steigen monoton um den ring`() {
        val mid = BandToChannel(spec).midHz
        assertEquals(36, mid.size)
        assertEquals(106.7, mid[0], 1.0)
        assertEquals(10327.9, mid[35], 30.0)
        for (p in 1 until mid.size) {
            assertTrue("Position $p: ${mid[p - 1]} -> ${mid[p]}", mid[p] > mid[p - 1])
        }
    }

    @Test
    fun `bei stille bleibt alles dunkel`() {
        val levels = levelsOf(silentBands())
        assertArrayEquals(IntArray(36), levels)
    }

    @Test
    fun `ein einzelnes lautes band leuchtet nur seine kanäle`() {
        val levels = levelsOf(brightBands(band = 8))
        // Band 8 (1050..1409 Hz) liegt auf Ringposition 17..20, also C13..C16
        // mit den SDK-Indizes 12..15.
        val lit = levels.indices.filter { levels[it] > 0 }
        assertEquals(listOf(12, 13, 14, 15), lit)
    }

    @Test
    fun `zwischen zwei bändern entsteht ein verlauf statt einer treppe`() {
        val levels = levelsOf(brightBands(band = 8))
        val four = levels.slice(12..15)
        assertEquals("gleiche Werte: $four", 4, four.distinct().size)
        assertEquals("C15 sitzt am Bandzentrum", 2, four.indexOf(four.max()))
    }

    @Test
    fun `das tieftonband hängt am tiefsten kanal`() {
        val levels = levelsOf(brightBands(band = BandToChannel.LOW_BAND_INDEX))
        val lit = levels.indices.filter { levels[it] > 0 }
        assertEquals(listOf(31), lit) // B1
    }

    @Test
    fun `zwei gleichlaute frames ergeben denselben wert`() {
        val mapper = BandToChannel(spec)
        val bands = brightBands(band = 8, db = -10f)
        val first = levelsOf(bands, mapper = mapper)
        val second = levelsOf(bands, mapper = mapper)
        assertArrayEquals(first, second)
    }

    @Test
    fun `der pegel bleibt in den grenzen des sdk`() {
        val bands = FloatArray(BandToChannel.BAND_COUNT) { 0f }
        val levels = levelsOf(bands, tension = 1f)
        for (value in levels) {
            assertTrue("Wert $value", value in 0..Level.MAX)
        }
        assertTrue(levels.any { it > 0 })
    }

    @Test
    fun `ein totes band bleibt dunkel`() {
        // Spanne P95 - P10 unter 6 dB: das Band rauscht nur, es trägt nichts.
        val p95 = FloatArray(BandToChannel.BAND_COUNT) { -20f }
        val p10 = FloatArray(BandToChannel.BAND_COUNT) { -23f }
        val mapper = BandToChannel(spec, p95, p10)
        val levels = levelsOf(brightBands(band = 8, db = -20f), mapper = mapper)
        assertArrayEquals(IntArray(36), levels)
    }

    @Test
    fun `nach stille fängt der ring wieder von vorn an`() {
        val mapper = BandToChannel(spec)
        val loud = brightBands(band = 8)
        levelsOf(loud, mapper = mapper)
        levelsOf(silentBands(), mapper = mapper)
        val again = levelsOf(loud, mapper = mapper)
        assertTrue(again.any { it > 0 })
    }
}
