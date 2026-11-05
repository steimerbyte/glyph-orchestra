package com.steimerbyte.glyphorchestra.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die drei Prüfungen aus Bericht §3.2, plus die Rücktransformation.
 *
 * Ein falsches Vorzeichen im Imaginärteil ist der Standardfehler einer
 * handgeschriebenen FFT und erzeugt ein gespiegeltes Spektrum -- das fällt nur
 * auf, wenn man die Phase mitprüft. Deshalb steht sie hier.
 */
class FftTest {

    private val n = 2048
    private val fs = 44100

    private fun sine(freq: Double, amp: Float = 1f): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / fs)).toFloat() }

    private fun hann(): FloatArray =
        FloatArray(n) { (0.5 * (1.0 - cos(2.0 * PI * it / n))).toFloat() }

    private fun ones(): FloatArray = FloatArray(n) { 1f }

    private fun mag(fft: Fft, k: Int): Double =
        sqrt(fft.re[k].toDouble() * fft.re[k] + fft.im[k].toDouble() * fft.im[k])

    private fun peakBin(fft: Fft): Int {
        var best = 0
        var bestPower = -1.0
        for (k in 0..n / 2) {
            val p = fft.re[k].toDouble() * fft.re[k] + fft.im[k].toDouble() * fft.im[k]
            if (p > bestPower) {
                bestPower = p
                best = k
            }
        }
        return best
    }

    @Test
    fun `ein Sinus landet im erwarteten Bin`() {
        val fft = Fft(n)
        val win = hann()
        for (freq in doubleArrayOf(440.0, 1000.0, 5000.0)) {
            fft.forward(sine(freq), 0, win)
            val erwartet = (freq * n / fs).roundToInt()
            val gefunden = peakBin(fft)
            assertTrue(
                "$freq Hz landete in Bin $gefunden, erwartet $erwartet (±1)",
                abs(gefunden - erwartet) <= 1,
            )
        }
    }

    @Test
    fun `ein Impuls bei 0 ergibt lauter Einsen`() {
        val fft = Fft(n)
        val x = FloatArray(n).also { it[0] = 1f }
        fft.forward(x, 0, ones())
        for (k in 0 until n) {
            assertEquals("Bin $k", 1.0, mag(fft, k), 1e-5)
        }
    }

    @Test
    fun `ein Vollaussteuerungssinus hat die analytische Amplitude`() {
        val fft = Fft(n)
        val k0 = 64
        // Cosinus statt Sinus: dann liegt der Peak reell, und die Phase ist prüfbar.
        val x = FloatArray(n) { cos(2.0 * PI * k0 * it / n).toFloat() }
        fft.forward(x, 0, hann())

        // |X[k0]| = A * S/2 = A * n/4 für A = 1 (Bericht §2.1).
        val erwartet = n / 4.0
        assertEquals(erwartet, mag(fft, k0), erwartet * 1e-3)
        // Hann-Hauptkeule: die Nachbarn tragen die halbe Amplitude.
        assertEquals(erwartet / 2, mag(fft, k0 - 1), erwartet * 1e-3)
        assertEquals(erwartet / 2, mag(fft, k0 + 1), erwartet * 1e-3)
        // Cosinus ist gerade: der Peak muss reell positiv sein, nicht imaginär.
        assertEquals(0.0, fft.im[k0].toDouble(), erwartet * 1e-3)
        assertTrue("Realteil ${fft.re[k0]} muss positiv sein", fft.re[k0] > 0f)
    }

    @Test
    fun `Parseval gilt auch mit Fenster`() {
        val fft = Fft(n)
        val win = hann()
        val rnd = Random(7)
        val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        fft.forward(x, 0, win)

        var spektral = 0.0
        for (k in 0 until n) {
            spektral += fft.re[k].toDouble() * fft.re[k] + fft.im[k].toDouble() * fft.im[k]
        }
        var zeit = 0.0
        for (i in 0 until n) {
            val v = x[i].toDouble() * win[i]
            zeit += v * v
        }
        assertEquals(n * zeit, spektral, n * zeit * 1e-4)
    }

    @Test
    fun `Hin- und Ruecktransformation trifft das Eingangssignal`() {
        val fft = Fft(n)
        val x = FloatArray(n) { i ->
            (0.8 * sin(2.0 * PI * 440.0 * i / fs) + 0.3 * cos(2.0 * PI * 1234.0 * i / fs)).toFloat()
        }
        fft.forward(x, 0, ones())
        fft.inverse()
        for (i in x.indices) {
            assertEquals("Sample $i", x[i].toDouble(), fft.re[i].toDouble(), 1e-4)
            assertEquals("Imaginärteil $i", 0.0, fft.im[i].toDouble(), 1e-4)
        }
    }
}
