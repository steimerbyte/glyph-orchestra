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
}
