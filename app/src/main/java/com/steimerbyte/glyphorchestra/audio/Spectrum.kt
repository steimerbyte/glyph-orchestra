package com.steimerbyte.glyphorchestra.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Nächste Zweierpotenz; die Fensterlänge soll bei jeder Rate ~21,5 Hz je Bin treffen. */
private fun nearestPow2(value: Double): Int {
    val low = Integer.highestOneBit(value.toInt().coerceAtLeast(1))
    return if (value - low < low * 2.0 - value) low else low * 2
}

/**
 * Hann-Fenster, Leistungsspektrum und die 17 Anzeigebänder eines Frames.
 *
 * Warum Fensterlänge und Bänder hier und nicht im Aufrufer: beides hängt an der
 * Abtastrate, und der Bericht legt beides über dieselbe Regel fest
 * (N = nächste Zweierpotenz zu fs/21,5). Wer [analyze] aufruft, liest [n]
 * Samples und bekommt [power] und [bandsDb] ohne eine einzige Allokation.
 *
 * [bandsDb] hat 17 Einträge: 0..15 sind die logarithmischen Dreiecksbänder
 * 100..11 025 Hz, Index 16 ist das Tieftonband 35..110 Hz für den Kick. Diese
 * Reihenfolge ist der Vertrag mit AnalysisResult (bandsDb[f * 17 + b]).
 *
 * Pegel: [power] ist so normiert, dass der Vollaussteuerungssinus im Peak-Bin
 * genau 1 ergibt, also 0 dBFS. Die Bänder sind flächennormiert (geteilt durch
 * die Bin-Zahl), wie Bericht §5.4 es verlangt -- dadurch liest ein breites Band
 * dasselbe Rauschsignal leiser als ein schmales. Für den Onset-Fluss fällt die
 * Konstante in der Differenz heraus, für die Anzeige normalisiert der
 * BandToChannel-Teil ohnehin je Band auf ein Perzentil.
 */
class Spectrum(val sampleRate: Int) {

    init {
        require(sampleRate > 0) { "Abtastrate muss positiv sein, war $sampleRate" }
    }

    /** Fensterlänge in Samples: 2048 bei 44,1 und 48 kHz, 1024 bei 22,05 kHz. */
    val n: Int = nearestPow2(sampleRate / TARGET_BIN_HZ)

    private val fft = Fft(n)

    /** Periodisches Hann (nicht symmetrisch): S = n/2, Bericht §2.1. */
    private val window = FloatArray(n) { (0.5 * (1.0 - cos(2.0 * PI * it / n))).toFloat() }

    /** 4/S² = 16/n²: |X|² mal dieser Faktor ist die Leistung relativ zum Vollausschlag. */
    private val powerScale = 16f / (n.toFloat() * n)

    /** Höchster ausgewerteter Bin: 11 025 Hz, aber nie über Nyquist. */
    private val kMax = minOf(n / 2, floor(HIGH_HZ / (sampleRate.toDouble() / n)).toInt())

    /** Anzahl ausgewerteter Bins, also [power].size. */
    val bins: Int = kMax + 1

    /**
     * Leistungsspektrum |X|², normiert auf Vollaussteuerung = 1.
     *
     * Wird von [analyze] neu geschrieben; der Aufrufer darf den Puffer lesen,
     * aber nicht behalten, wenn er ihn nicht selbst kopiert.
     */
    val power = FloatArray(bins)

    /** Bandpegel in dBFS, Index 0..15 logarithmisch, Index 16 Tieftonband. */
    val bandsDb = FloatArray(BAND_COUNT)

    /** Untere Kante je Band in Hz; Index 16 ist das Tieftonband. */
    val bandLowHz = DoubleArray(BAND_COUNT)

    /** Obere Kante je Band in Hz; Index 16 ist das Tieftonband. */
    val bandHighHz = DoubleArray(BAND_COUNT)

    /** Dichte Filterbank, Zeilen = Bänder: Gewicht der Bin k in Band b. */
    private val tris = FloatArray(BAND_COUNT * bins)

    /** 1/Bin-Zahl je Band -- die Flächennormierung aus §5.4. */
    private val norm = FloatArray(BAND_COUNT)

    init {
        buildBands()
    }

    /**
     * Analysiert `x[off ..< off+n]`, füllt [power] und [bandsDb].
     *
     * Keine Rückgabe und keine Objekte: der Aufrufer hält die Puffer, der
     * Analysepfad läuft 86-mal je Sekunde über dieselben Arrays.
     */
    fun analyze(x: FloatArray, off: Int = 0) {
        fft.forward(x, off, window)
        val re = fft.re
        val im = fft.im
        var k = 0
        while (k < bins) {
            val r = re[k]
            val i = im[k]
            power[k] = (r * r + i * i) * powerScale
            k++
        }
        var b = 0
        while (b < BAND_COUNT) {
            val row = b * bins
            var sum = 0f
            k = 0
            while (k < bins) {
                sum += tris[row + k] * power[k]
                k++
            }
            bandsDb[b] = 10f * log10(sum * norm[b] + EPS)
            b++
        }
    }

    /**
     * Baut die Dreiecksfilter aus Hz-Kanten. Die Bin-Grenzen werden zur Laufzeit
     * aus f/Δf gerechnet und nicht aus der Tabelle des Berichts übernommen:
     * bei 48 kHz verschieben sie sich um 9 %.
     */
    private fun buildBands() {
        val df = sampleRate.toDouble() / n
        val r = (HIGH_HZ / LOW_HZ).pow(1.0 / Dsp.BANDS)
        for (b in 0 until Dsp.BANDS) {
            val lo = LOW_HZ * r.pow(b.toDouble())
            val hi = LOW_HZ * r.pow(b.toDouble() + 1.0)
            bandLowHz[b] = lo
            bandHighHz[b] = hi
            val centre = sqrt(lo * hi)
            val kLo = ceil(lo / df).toInt().coerceIn(0, kMax)
            var kHi = floor(hi / df).toInt().coerceIn(0, kMax)
            // Bei 48 kHz fällt das erste Band auf eine einzige Bin; zwei Bins
            // halten das Dreieck definiert (Bericht: mindestens 2 Bins je Band).
            if (kHi <= kLo) kHi = minOf(kLo + 1, kMax)
            val row = b * bins
            for (k in kLo..kHi) {
                val f = k * df
                val w = if (f <= centre) (f - lo) / (centre - lo) else (hi - f) / (hi - centre)
                tris[row + k] = w.coerceIn(0.0, 1.0).toFloat()
            }
            norm[b] = 1f / (kHi - kLo + 1)
        }

        // Tieftonband als Rechteck: hier zählt die reine Bandleistung des Kicks,
        // keine Glocke um eine Mittenfrequenz. Es überlappt bewusst mit dem
        // untersten Log-Band -- der Kick soll auch dort sichtbar bleiben.
        val low = Dsp.BANDS
        bandLowHz[low] = Dsp.LOW_BAND_HZ_FROM
        bandHighHz[low] = Dsp.LOW_BAND_HZ_TO
        val kLo = ceil(Dsp.LOW_BAND_HZ_FROM / df).toInt().coerceIn(0, kMax)
        val kHi = floor(Dsp.LOW_BAND_HZ_TO / df).toInt().coerceIn(kLo, kMax)
        val row = low * bins
        for (k in kLo..kHi) tris[row + k] = 1f
        norm[low] = 1f / (kHi - kLo + 1)
    }

    companion object {
        /** 16 logarithmische Bänder plus das Tieftonband. */
        const val BAND_COUNT = Dsp.BANDS + 1

        /** Index des Tieftonbands in [bandsDb]. */
        const val LOW_BAND = Dsp.BANDS

        /** Ziel-Bin-Breite für die Fensterlänge aus Bericht §1.6. */
        private const val TARGET_BIN_HZ = 21.5

        /** Erste Bandkante; 16 Schritte bis [HIGH_HZ] ergeben r = 1,3417. */
        private const val LOW_HZ = 100.0

        /** Analysegrenze; oberhalb davon ist Musik ohnehin nur noch Air. */
        private const val HIGH_HZ = 11025.0

        /** Floor bei -120 dBFS, Bericht §4. */
        private const val EPS = 1e-12f
    }
}
