package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.glyph.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * Randfaelle, die im Betrieb vorkommen und [AnalyzerTest] nicht abdeckt:
 * andere Abtastraten (48 und 22,05 kHz), eine 300-ms-Datei, eine leere Datei,
 * der volle Dynamikbereich und ein Song mit Tempowechsel.
 *
 * Die Analyse-Bildrate haengt an fs/HOP und darf nirgends auf 86,1 festgenagelt
 * sein; die Tests lesen sie deshalb aus dem Ergebnis und nicht aus einer
 * Konstante.
 */
class AnalyzerEdgeCaseTest {

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

    /** Quelle ohne einen einzigen Frame, wie eine 0-Byte-Datei sie liefert. */
    private class EmptySource(override val sampleRate: Int) : PcmSource {
        override fun read(dst: FloatArray): Int = -1
    }

    /** Rauschimpuls mit 1,5-ms-Abklingen, wie ein Klick eines Drumcomputers. */
    private fun putClick(out: FloatArray, sr: Int, timeSec: Double, amplitude: Float) {
        val start = (timeSec * sr).toInt()
        if (start >= out.size) return
        val len = (0.006 * sr).toInt().coerceAtMost(out.size - start)
        var seed = 1234567
        for (i in 0 until len) {
            seed = seed * 1103515245 + 12345
            val noise = ((seed ushr 8) and 0xFFFF) / 32768f - 1f
            out[start + i] += noise * exp(-i / (0.0015 * sr)).toFloat() * amplitude
        }
    }

    private fun clickTrack(
        seconds: Double,
        bpm: Double,
        sr: Int,
        amplitude: Float = 0.8f,
    ): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        var t = 0.3
        while (t < seconds) {
            putClick(out, sr, t, amplitude)
            t += 60.0 / bpm
        }
        return out
    }

    private val spec = DeviceCatalog.PHONE_3A

    private fun assertFrameShapes(r: AnalysisResult) {
        assertTrue("Frames ${r.frames}", r.frames >= 1)
        assertEquals("levels ist nicht frames*36", r.frames * 36, r.levels.size)
        assertEquals("tension ist nicht frames", r.frames, r.tension.size)
        assertTrue("Helligkeit ausserhalb 0..${Level.MAX}", r.levels.all { it in 0..Level.MAX })
        assertTrue("Spannung ausserhalb 0..1", r.tension.all { it in 0f..1f })
        assertTrue("bandsDb nicht endlich", r.bandsDb.all { it.isFinite() })
    }

    @Test
    fun `48 khz klickzug liefert tempo und rechnet mit fs durch hop`() {
        val r = Analyzer.analyze(ArraySource(48000, clickTrack(8.0, 120.0, 48000)), spec)

        // 48000/512 = 93,75. Eine hartkodierte 86,1 waere hier sofort sichtbar.
        assertEquals(93.75, r.fpsAnalysis, 1e-9)
        assertEquals(30, r.fpsDraw)
        assertEquals(120.0, r.bpm, 2.0)
        assertTrue("Beats: ${r.beats.size}", r.beats.size >= 12)
        assertTrue("Dauer ${r.durationSec}", abs(r.durationSec - 8.0) < 0.1)
        assertTrue("Anzeigeframes: ${r.frames}", r.frames in 235..245)
        assertFrameShapes(r)
    }

    @Test
    fun `22 khz bricht nicht und rechnet mit fs durch hop`() {
        val r = Analyzer.analyze(ArraySource(22050, clickTrack(8.0, 120.0, 22050)), spec)

        // 22050/512 = 43,066. Auch hier darf nichts auf 86,1 stehen.
        assertEquals(22050.0 / Dsp.HOP, r.fpsAnalysis, 1e-9)
        assertEquals(30, r.fpsDraw)
        assertTrue("Dauer ${r.durationSec}", abs(r.durationSec - 8.0) < 0.1)
        assertTrue("Anzeigeframes: ${r.frames}", r.frames in 235..245)
        assertFrameShapes(r)

        // Ein Klickzug muss auch bei halber Rate erkannt werden.
        assertEquals(120.0, r.bpm, 2.0)
        assertTrue("Beats: ${r.beats.size}", r.beats.size >= 12)
        for (i in 1 until r.beats.size) {
            assertTrue("Beats nicht aufsteigend: ${r.beats.toList()}", r.beats[i] > r.beats[i - 1])
        }
        assertTrue("Beat hinter dem Ende: ${r.beats.last()}", r.beats.last() <= r.durationSec + 0.05)
    }

    @Test
    fun `eine datei von 300 ms liefert mindestens einen frame`() {
        val short = FloatArray((0.3 * 44100).toInt())
        putClick(short, 44100, 0.05, 0.8f)
        val r = Analyzer.analyze(ArraySource(44100, short), spec)

        assertFrameShapes(r)
        // 300 ms reichen fuer keine Periodizitaet; es darf nur kein Tempo
        // erfunden werden.
        assertEquals(0.0, r.bpm, 0.0)
        assertEquals(0, r.beats.size)
        assertTrue("Dauer ${r.durationSec}", r.durationSec > 0.0)
    }

    @Test
    fun `eine leere datei stuerzt nicht ab`() {
        val r = Analyzer.analyze(EmptySource(44100), spec)

        assertFrameShapes(r)
        assertTrue("Leere Datei zeigt etwas", r.levels.all { it == 0 })
        assertEquals(0.0, r.bpm, 0.0)
    }

    @Test
    fun `minus 60 dbfs laeuft nicht ueber und bleibt messbar`() {
        // amplitude 0,001 = -60 dBFS Spitze. Die Anzeige ist relativ zum
        // Band-P95, darf hier also ruhig dunkel bleiben; entscheidend ist,
        // dass nichts ueberlaeuft und das Tempo noch gefunden wird.
        val quiet = clickTrack(4.0, 120.0, 44100, amplitude = 0.001f)
        val r = Analyzer.analyze(ArraySource(44100, quiet), spec)

        assertFrameShapes(r)
        assertEquals(120.0, r.bpm, 2.0)
        assertTrue("Beats: ${r.beats.size}", r.beats.size >= 4)
    }

    @Test
    fun `vollaussteuerung laeuft nicht ueber`() {
        val loud = clickTrack(4.0, 120.0, 44100, amplitude = 1f)
        val r = Analyzer.analyze(ArraySource(44100, loud), spec)

        assertFrameShapes(r)
        assertTrue("Vollaussteuerung zeigt nichts", r.levels.any { it > 0 })
        assertEquals(120.0, r.bpm, 2.0)
    }

    @Test
    fun `tempowechsel von 60 auf 120 bpm bricht die konfidenz nicht ein`() {
        val sr = 44100
        val out = FloatArray(8 * sr)
        val clicks = ArrayList<Double>()
        var slow = 0.3
        while (slow < 4.0) {
            putClick(out, sr, slow, 0.8f)
            clicks.add(slow)
            slow += 1.0
        }
        var fast = 4.3
        while (fast < 8.0) {
            putClick(out, sr, fast, 0.8f)
            clicks.add(fast)
            fast += 0.5
        }
        val r = Analyzer.analyze(ArraySource(sr, out), spec)

        assertFrameShapes(r)
        assertTrue("Konfidenz ${r.confidence}", r.confidence > 0.3)
        assertTrue("Beats: ${r.beats.size}", r.beats.size in 6..20)
        assertTrue("Tempo ${r.bpm}", r.bpm in 55.0..125.0)
        for (i in 1 until r.beats.size) {
            assertTrue("Beats nicht aufsteigend: ${r.beats.toList()}", r.beats[i] > r.beats[i - 1])
        }
        // Plausibel heisst: jeder der zwoelf Klicks hat einen Beat. Ob der
        // Tracker dabei 120 durchhaelt und die 60er-Klicks als Eins mitnimmt
        // oder zwischen beiden Niveaus wechselt, ist seine Entscheidung.
        for (click in clicks) {
            val nearest = r.beats.minByOrNull { abs(it - click) } ?: -1.0
            assertTrue("Klick bei $click hat keinen Beat, naechster $nearest", abs(nearest - click) <= 0.080)
        }
    }
}
