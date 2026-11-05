package com.steimerbyte.glyphorchestra.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative Radix-2-FFT fester Größe, reell hinein, komplex in [re]/[im].
 *
 * Warum Eigenbau statt JTransforms: bei N = 2048 ist die FFT kein Engpass
 * (Bericht §3.3), die Bibliothek brächte einen prozessweiten Thread-Pool und
 * zwei Artefakte in die APK -- und sie kann Fenstern und Bit-Reversal nicht in
 * denselben Durchlauf falten, wie es hier passiert.
 *
 * Alles, was pro Frame gebraucht wird (Bit-Reversal-Tabelle, Twiddles, re/im),
 * entsteht einmal im Konstruktor. [forward] und [inverse] legen nichts an: der
 * Analysepfad ruft sie 86-mal je Sekunde.
 */
class Fft(val n: Int) {

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "n muss eine Zweierpotenz >= 2 sein, war $n" }
    }

    private val bits = Integer.numberOfTrailingZeros(n)

    /** Zielindex des Eingabesamples i -- die Spiegelung der unteren [bits] Bit. */
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }

    /** cos(2πk/n) bzw. sin(2πk/n); der Butterfly greift mit der Schrittweite n/len zu. */
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }

    /** Realteil des Spektrums, gültig nach [forward]. */
    val re = FloatArray(n)

    /** Imaginärteil des Spektrums, gültig nach [forward]. */
    val im = FloatArray(n)

    /**
     * Füllt re/im aus `x[off ..< off+n]` und transformiert in place.
     *
     * Das Fenstern ist mit dem Bit-Reversal gefaltet: jedes Sample wird genau
     * einmal gelesen, einmal multipliziert und an seine gespiegelte Stelle
     * geschrieben. Das spart einen zweiten Durchlauf über 2048 Werte pro Frame.
     */
    fun forward(x: FloatArray, off: Int, window: FloatArray) {
        require(off >= 0 && off + n <= x.size) { "x zu kurz: ${x.size}, gebraucht ${off + n}" }
        require(window.size >= n) { "window zu kurz: ${window.size}, gebraucht $n" }
        var i = 0
        while (i < n) {
            val j = rev[i]
            re[j] = x[off + i] * window[i]
            im[j] = 0f
            i++
        }
        butterflies()
    }

    /**
     * Rücktransformation der aktuellen re/im in place, skaliert mit 1/n.
     *
     * Nur für Tests und Kontrollrechnungen gedacht: die App rekonstruiert nie
     * Audio, sie liest das Spektrum. Vorwärts und inverse teilen sich die
     * Twiddles, weil die inverse Transformation die konjugierte Vorwärts-
     * transformation ist: konjugieren, vorwärts, konjugieren, durch n teilen.
     */
    fun inverse() {
        var i = 0
        while (i < n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            i++
        }
        for (k in 0 until n) im[k] = -im[k]
        butterflies()
        val scale = 1f / n
        for (k in 0 until n) {
            re[k] *= scale
            im[k] = -im[k] * scale
        }
    }

    /**
     * Der DIT-Kern: für jede Stufe len werden die Blöcke halbiert und mit
     * w = cos - i·sin verknüpft. Das Vorzeichen von vi ist die klassische
     * Fehlerstelle (Bericht §3.2); die Vorzeichenprobe steht in FftTest.
     */
    private fun butterflies() {
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var block = 0
            while (block < n) {
                val end = block + half
                var j = block
                var k = 0
                while (j < end) {
                    val c = cosT[k]
                    val s = sinT[k]
                    val a = j + half
                    val ar = re[a]
                    val ai = im[a]
                    val vr = ar * c + ai * s
                    val vi = ai * c - ar * s
                    val ur = re[j]
                    val ui = im[j]
                    re[j] = ur + vr
                    im[j] = ui + vi
                    re[a] = ur - vr
                    im[a] = ui - vi
                    k += step
                    j++
                }
                block += len
            }
            len = len shl 1
        }
    }
}
