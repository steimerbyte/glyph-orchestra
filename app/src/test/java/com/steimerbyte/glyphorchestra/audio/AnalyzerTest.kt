package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Prueft die Kette Analyse -> Spannung -> 36 Kanaele an einem Signal, dessen
 * Tempo wir kennen. Ohne diesen Test waere jeder Fehler in der Verkettung
 * erst am Geraet als "die Glyphen blinken komisch" sichtbar.
 */
class AnalyzerTest {

    private class ArraySource(
        override val sampleRate: Int,
        private val samples: FloatArray,
    ) : PcmSource {
        private var pos = 0
        override fun read(dst: FloatArray): Int {
            if (pos >= samples.size) return -1
            val n = minOf(dst.size, samples.size - pos)
            System.arraycopy(samples, pos, dst, 0, n)
            pos += n
            return n
        }
    }

    /** Klickzug: kurzer Rauschimpuls alle [beatSec] Sekunden. */
    private fun clickTrack(seconds: Double, bpm: Double, sr: Int = 44100): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        val beat = 60.0 / bpm
        var t = 0.0
        var seed = 1234567
        while (t < seconds) {
            val start = (t * sr).toInt()
            val len = (0.006 * sr).toInt()
            for (i in 0 until len) {
                if (start + i >= out.size) break
                seed = seed * 1103515245 + 12345
                val noise = ((seed ushr 8) and 0xFFFF) / 32768f - 1f
                out[start + i] = noise * exp(-i / (0.0015 * sr)).toFloat()
            }
            t += beat
        }
        return out
    }

    private fun tone(seconds: Double, hz: Double, sr: Int = 44100): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        for (i in out.indices) out[i] = (0.8 * sin(2 * PI * hz * i / sr)).toFloat()
        return out
    }

    private val spec = DeviceCatalog.PHONE_3A

    /** Helligkeit eines Anzeigeframes: der hellste der 36 Kanaele. */
    private fun brightest(r: AnalysisResult, frame: Int): Int =
        (frame * 36 until (frame + 1) * 36).maxOf { r.levels[it] }

    @Test
    fun `ein einsatz bei 1 komma 3 sekunden landet auf anzeigeframe 39`() {
        // 1,3 s sind 39 Anzeigeframes zu 30 fps. Der Analyseframe, dessen
        // Fenstermitte dort liegt, ist 39*86,133/30 - 2 = 110; ohne den Abzug
        // der Fenstermitte liegt der Einsatz bei Frame 38.
        //
        // Signal: Stille bis 1,3 s, danach ein Dauerton. Das erste Bild, das
        // hell wird, ist damit die Abbildung dieses Einsatzes.
        val sr = 44100
        val pcm = FloatArray(2 * sr)
        val start = (1.3 * sr).toInt()
        for (i in start until pcm.size) {
            pcm[i] = (0.9 * sin(2 * PI * 1000 * (i - start) / sr)).toFloat()
        }

        val r = Analyzer.analyze(ArraySource(sr, pcm), spec)
        val firstLit = (0 until r.frames).first { brightest(r, it) > 0 }
        assertEquals("Einsatz bei Frame $firstLit statt 39", 39, firstLit)
    }

    @Test
    fun `novelty misst die timbre-aenderung, nicht die lautheit`() {
        val novelty = TimbreNovelty(Dsp.analysisFps(44100))
        val count = BandToChannel.BAND_COUNT

        // Ein Kick: dieselbe dB-Form, nur 30 dB lauter. Der Onset-Fluss
        // schlaegt hier voll aus, die Timbre-Novelty darf es nicht.
        val lowHeavy = FloatArray(count) { b -> -4f * b }
        val loud = FloatArray(count) { b -> -4f * b + 30f }
        novelty.push(lowHeavy)
        val kick = maxOf(novelty.push(loud), novelty.push(loud))
        assertTrue("Kick erzeugt Novelty $kick", kick < 0.01f)

        // Ein Instrumentenwechsel: die Hoehen uebernehmen, die Tiefen fallen
        // weg -- eine andere Richtung, nicht nur ein anderer Pegel. Im
        // Wechsel ist die Rohdistanz in jedem Frame gleich gross; die EMA
        // naehert sich ihr mit tau = 200 ms.
        val highHeavy = FloatArray(count) { b -> -4f * (16 - b) }
        var up = false
        var after200Ms = 0f
        repeat(17) {
            after200Ms = novelty.push(if (up) lowHeavy else highHeavy)
            up = !up
        }
        var settled = 0f
        repeat(200) {
            settled = novelty.push(if (up) lowHeavy else highHeavy)
            up = !up
        }
        assertTrue("Wechsel ergibt nur $settled", settled > 0.3f)
        // EMA mit tau = 200 ms: nach 17 Frames sind es 1 - exp(-1) = 63 %.
        assertEquals(0.63, (after200Ms / settled).toDouble(), 0.05)
    }

    @Test
    fun `klickzug wird als 120 bpm erkannt`() {
        val r = Analyzer.analyze(ArraySource(44100, clickTrack(8.0, 120.0)), spec)
        assertEquals(30, r.fpsDraw)
        assertTrue("Frames erwartet", r.frames > 200)
        assertEquals(120.0, r.bpm, 2.0)
        assertTrue("Beats erwartet, waren ${r.beats.size}", r.beats.size >= 12)
        // Der erste Beat darf nicht erst nach einer Sekunde kommen.
        assertTrue("erster Beat bei ${r.beats.first()}", r.beats.first() < 0.6)
    }

    @Test
    fun `helligkeit bleibt im bereich und ist nicht durchgehend null`() {
        val r = Analyzer.analyze(ArraySource(44100, clickTrack(4.0, 120.0)), spec)
        assertTrue(r.levels.any { it > 0 })
        assertTrue("Werte ueber 4000", r.levels.all { it <= 4000 })
        assertTrue("Spannung ausserhalb 0..1", r.tension.all { it in 0f..1f })
    }

    @Test
    fun `stille schaltet alles aus`() {
        val r = Analyzer.analyze(ArraySource(44100, FloatArray(44100 * 2)), spec)
        assertTrue("Stille darf nichts anzeigen", r.levels.all { it == 0 })
    }

    @Test
    fun `dauerklang liefert keine beats`() {
        val r = Analyzer.analyze(ArraySource(44100, tone(4.0, 440.0)), spec)
        assertTrue("Konfidenz ${r.confidence}", r.confidence < 0.5)
    }

    @Test
    fun `die spannung haengt am beat-raster`() {
        // Klicks bis 3 s, danach eine Sekunde Stille. Hinter dem letzten Beat
        // haelt quantize die Spannung auf dem Wert des letzten Rasterpunkts;
        // ohne Quantisierung faellt sie in der Stille mit tau = 0,4 s ab.
        val head = clickTrack(3.2, 120.0)
        val pcm = FloatArray(4 * 44100)
        System.arraycopy(head, 0, pcm, 0, head.size)
        val r = Analyzer.analyze(ArraySource(44100, pcm), spec)

        assertTrue("Beats: ${r.beats.size}", r.beats.size >= 5)
        val lastBeat = r.beats.last()
        assertTrue("letzter Beat bei $lastBeat", lastBeat < 3.5)
        val from = (lastBeat * 30).toInt() + 2
        assertTrue("nur ${r.frames} Frames", from < r.frames - 5)
        val held = r.tension[from]
        assertTrue("Spannung hinter dem letzten Beat ist $held", held > 0.02f)
        for (m in from until r.frames) {
            assertEquals("Frame $m weicht ab", held, r.tension[m], 0f)
        }
    }
}
