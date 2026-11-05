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

    /** Analyse-Bildrate bei 44,1 kHz: 86,133 Hz, wie im Betrieb. */
    private val fps = Dsp.analysisFps(44100)

    private fun silentBands(): FloatArray = FloatArray(BandToChannel.BAND_COUNT) { -100f }

    private fun brightBands(band: Int, db: Float = 0f): FloatArray =
        silentBands().also { it[band] = db }

    private fun levelsOf(bands: FloatArray, tension: Float = 0f, mapper: BandToChannel = BandToChannel(spec, fps)): IntArray {
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
        assertArrayEquals(expected, BandToChannel(spec, fps).ring)
    }

    @Test
    fun `die mittenfrequenzen steigen monoton um den ring`() {
        val mid = BandToChannel(spec, fps).midHz
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
        val mapper = BandToChannel(spec, fps)
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
        val mapper = BandToChannel(spec, fps, p95, p10)
        val levels = levelsOf(brightBands(band = 8, db = -20f), mapper = mapper)
        assertArrayEquals(IntArray(36), levels)
    }

    @Test
    fun `nach stille fängt der ring wieder von vorn an`() {
        val mapper = BandToChannel(spec, fps)
        val loud = brightBands(band = 8)
        levelsOf(loud, mapper = mapper)
        levelsOf(silentBands(), mapper = mapper)
        val again = levelsOf(loud, mapper = mapper)
        assertTrue(again.any { it > 0 })
    }

    /**
     * Ein lauter Frame, danach leise aber nicht still: so lange der Kanal
     * eingerastet bleibt, ist der Wert oben. Gezählt wird, wie lange der
     * Spitzenwert stehen bleibt -- das ist die Mindesthaltezeit.
     */
    private fun framesAtPeak(rate: Double): Int {
        val mapper = BandToChannel(spec, rate)
        val bright = FloatArray(BandToChannel.BAND_COUNT) { 0f }
        val quiet = FloatArray(BandToChannel.BAND_COUNT) { -60f }
        val levels = IntArray(36)
        mapper.map(bright, 0f, levels)
        val peak = levels.max()
        var frames = 1
        while (frames <= 200) {
            mapper.map(quiet, 0f, levels)
            if (levels.any { it != peak }) break
            frames++
        }
        return frames
    }

    @Test
    fun `die haltezeit betraegt bei der analyserate mindestens 133 ms`() {
        // 86,133 fps: 12 Frames = 139 ms. Mit der alten festen Zahl 4 waeren
        // es 46 ms gewesen, mit round(0,133*fps) nur 11 Frames = 128 ms.
        val held = framesAtPeak(fps)
        val heldMs = held * 1000.0 / fps
        assertTrue("nur $held Frames = $heldMs ms", heldMs >= 133.0)
    }

    @Test
    fun `bei 30 fps sind es wie im bericht vier frames`() {
        // Dieselbe Haltezeit an der Rate, fuer die der Bericht sie angibt:
        // 4 Frames = 133 ms. Das belegt, dass die Zahl aus der Rate kommt.
        assertEquals(4, framesAtPeak(30.0))
    }

    @Test
    fun `der release faellt mit 45 db pro sekunde`() {
        // 45 dB/s im 30-dB-Fenster sind 1,5 normierte Einheiten/s, also
        // 1,5*4095/fps je Frame. Bei 86,133 fps sind das 71 Rohwerte, nicht
        // 205 -- die 205 gelten nur bei 30 fps.
        val mapper = BandToChannel(spec, fps)
        val bright = FloatArray(BandToChannel.BAND_COUNT) { 0f }
        val quiet = FloatArray(BandToChannel.BAND_COUNT) { -60f }
        val levels = IntArray(36)
        mapper.map(bright, 0f, levels)
        val peak = levels.max()
        var previous = peak
        var drop = 0
        while (drop == 0) {
            mapper.map(quiet, 0f, levels)
            val now = levels.max()
            if (now < previous) drop = previous - now
            previous = now
        }
        val perSecond = drop * fps
        assertEquals(1.5 * 4095, perSecond, 0.03 * 1.5 * 4095)
    }

    @Test
    fun `der spannungssockel schaltet keinen dunklen kanal ein`() {
        // y = 0,033 in jedem Band: ueber dem Sockel-Mindestwert 0,02, aber
        // unter der Ausschaltschwelle 0,04. Mit dem Sockel auf dunklen
        // Kanaelen (T*492 = 492 >= 328) waere der ganze Ring an -- und
        // ginge nie wieder aus, weil der Ausschaltpunkt unerreichbar ist.
        val bands = FloatArray(BandToChannel.BAND_COUNT) { -35f }
        val levels = IntArray(36)
        BandToChannel(spec, fps).map(bands, 1f, levels)
        assertArrayEquals(IntArray(36), levels)
    }

    @Test
    fun `ein eingerasteter kanal bekommt den sockel zu sehen`() {
        val bands = FloatArray(BandToChannel.BAND_COUNT) { 0f }
        val cold = IntArray(36)
        val warm = IntArray(36)
        BandToChannel(spec, fps).map(bands, 0f, cold)
        BandToChannel(spec, fps).map(bands, 1f, warm)
        // Der Sockel hebt den Leuchtenden, statt ihn neu einzuschalten.
        assertEquals(Level.MAX, warm.max())
        assertTrue("ohne Spannung ${cold.max()}", cold.max() < warm.max())
    }

    @Test
    fun `die einschaltschwelle liegt bei 0 komma 08 der helligkeit`() {
        // y = (dB + 36)/30 bei den Standard-Perzentilen. 0,117 liegt ueber
        // 0,08 und muss leuchten; am gamma-korrigierten Rohwert (86 < 328)
        // bliebe der Ring dunkel.
        val justAbove = FloatArray(BandToChannel.BAND_COUNT) { -32.5f }
        val justBelow = FloatArray(BandToChannel.BAND_COUNT) { -34f }
        val on = IntArray(36)
        val off = IntArray(36)
        BandToChannel(spec, fps).map(justAbove, 0f, on)
        BandToChannel(spec, fps).map(justBelow, 0f, off)
        assertTrue("y = 0,117 leuchtet nicht", on.all { it > 0 })
        assertArrayEquals(IntArray(36), off)
    }

    @Test
    fun `ein kanal bleibt bei hoher spannung nicht eingerastet`() {
        val mapper = BandToChannel(spec, fps)
        val levels = IntArray(36)
        mapper.map(FloatArray(BandToChannel.BAND_COUNT) { 0f }, 1f, levels)
        assertTrue("Ring leuchtet nicht", levels.all { it > 0 })

        // Band auf y = 0,033, also unter der Ausschaltschwelle 0,04 -- bei
        // voller Spannung. Mit dem Sockel auf dem Zielwert bliebe der Kanal
        // eingerastet und leuchtete mit 492 weiter, ohne je auszugehen.
        val quiet = FloatArray(BandToChannel.BAND_COUNT) { -35f }
        repeat(90) { mapper.map(quiet, 1f, levels) }
        assertArrayEquals(IntArray(36), levels)
        repeat(10) {
            mapper.map(quiet, 1f, levels)
            assertArrayEquals(IntArray(36), levels)
        }
    }

    @Test
    fun `stille blendet erst nach 300 ms aus und endet exakt bei null`() {
        val mapper = BandToChannel(spec, fps)
        val bright = FloatArray(BandToChannel.BAND_COUNT) { 0f }
        val silent = silentBands()
        val levels = IntArray(36)

        mapper.map(bright, 0f, levels)
        assertTrue(levels.all { it > 0 })

        // Kurze Luecke: 26 stille Frames sind genau die Entprellung, der
        // Ring bleibt stehen, statt bei jeder Phrasenluecke zu erloeschen.
        repeat(26) {
            mapper.map(silent, 0f, levels)
            assertTrue("Frame $it schon dunkel: ${levels.toList()}", levels.all { it > 0 })
        }

        // Danach 150 ms Ausblendung (13 Frames) und dann exakt null.
        repeat(13) { mapper.map(silent, 0f, levels) }
        assertArrayEquals(IntArray(36), levels)
        repeat(10) {
            mapper.map(silent, 0f, levels)
            assertArrayEquals(IntArray(36), levels)
        }
    }
}
