package com.steimerbyte.glyphorchestra.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Die Merkmale eines Analysefensters -- reine Rechnung auf dem Leistungsspektrum.
 *
 * Warum freie Funktionen und kein Objekt mit Zustand: der Analysepfad ruft sie
 * 86-mal je Sekunde auf, und jede Allokation in dieser Schleife wäre Müll.
 * Alle Puffer kommen von außen, keine Funktion hier legt ein Array an --
 * deshalb gibt es auch kein `sumOf`, das intern eine Closure und einen
 * Iterator anlegt.
 *
 * [BIN_FROM]..[BIN_TO] ist der Bereich 100..11025 Hz bei 44,1 kHz und N = 2048.
 * Bei anderen Abtastraten ist das Spektrum kuerzer, deshalb begrenzt jeder
 * Aufruf die Obergrenze zusaetzlich auf power.lastIndex.
 * (Binbreite 21,533 Hz). Wer mit anderer Rate oder Fenstergröße rechnet,
 * gibt die Grenzen selbst an; die Vorgaben sind der Pop-Fall aus dem Bericht.
 */
object Features {

    /** Erste genutzte Bin: 5 x 21,533 Hz = 107,7 Hz. */
    const val BIN_FROM = 5

    /** Letzte genutzte Bin: 512 x 21,533 Hz = 11 025 Hz. */
    const val BIN_TO = 512

    /**
     * Spektraler Schwerpunkt in Hz, leistungsgewichtet.
     *
     * Leistung statt Betrag ist robuster gegen Rauschen und entspricht
     * MPEG-7; ein einzelner 1-kHz-Sinus landet damit auf 1 kHz.
     */
    fun centroid(
        power: FloatArray,
        binHz: Double,
        from: Int = BIN_FROM,
        to: Int = minOf(BIN_TO, power.lastIndex),
    ): Double {
        var moment = 0.0
        var total = 0.0
        for (k in from..to) {
            val p = power[k].toDouble()
            moment += k * p
            total += p
        }
        return if (total > 0.0) binHz * moment / total else 0.0
    }

    /**
     * Bandbreite (Standardabweichung um [centroid]) in Hz.
     *
     * Nur ein Plausibilitätsmaß: unter 150 Hz ist der Frame fast ein reiner
     * Ton, und dann darf die Rauheitsrechnung gar nicht erst anspringen.
     */
    fun spread(
        power: FloatArray,
        binHz: Double,
        centroidHz: Double,
        from: Int = BIN_FROM,
        to: Int = minOf(BIN_TO, power.lastIndex),
    ): Double {
        var variance = 0.0
        var total = 0.0
        for (k in from..to) {
            val p = power[k].toDouble()
            val d = k * binHz - centroidHz
            variance += d * d * p
            total += p
        }
        return if (total > 0.0) sqrt(variance / total) else 0.0
    }

    /**
     * Rolloff bei 85 % der Energie in Hz.
     *
     * Die Kante, unterhalb derer 85 % der Leistung liegen -- der billigste
     * Dichte-Indikator: Strophe 2-4 kHz, Refrain 4-7 kHz.
     */
    fun rolloff85(
        power: FloatArray,
        binHz: Double,
        from: Int = BIN_FROM,
        to: Int = minOf(BIN_TO, power.lastIndex),
    ): Double {
        var total = 0.0
        for (k in from..to) total += power[k]
        if (total <= 0.0) return 0.0
        val limit = 0.85 * total
        var acc = 0.0
        for (k in from..to) {
            acc += power[k]
            if (acc >= limit) return k * binHz
        }
        return to * binHz
    }

    /**
     * Flatness (Wiener-Entropie), 0 = tonal, 1 = rauschartig.
     *
     * Das Epsilon hängt am Framemaximum statt an einer festen Zahl, damit
     * die Rechnung unabhängig von der FFT-Normierung ist: sonst wäre der
     * Wert bei leisen Frames ein anderer als bei lauten desselben Materials.
     *
     * Nicht als Spannungstreiber verwenden -- die Flatness folgt der
     * Beckendichte, nicht der musikalischen Spannung. Sie dient als Gate für
     * die Rauheit.
     */
    fun flatness(
        power: FloatArray,
        from: Int = BIN_FROM,
        to: Int = minOf(BIN_TO, power.lastIndex),
    ): Double {
        var maxPower = 0f
        for (k in from..to) if (power[k] > maxPower) maxPower = power[k]
        if (maxPower <= 0f) return 0.0
        val eps = 1e-12 * maxPower
        var logSum = 0.0
        var sum = 0.0
        for (k in from..to) {
            val p = power[k] + eps
            logSum += ln(p)
            sum += p
        }
        val count = to - from + 1
        if (count <= 0) return 0.0
        val geometric = exp(logSum / count)
        val arithmetic = sum / count
        return if (arithmetic > 0.0) (geometric / arithmetic).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Nullstellendichte in Hz -- bei einem Einzelton näherungsweise seine
     * Frequenz.
     *
     * Der Bericht schreibt die Summe der Beträge der Vorzeichendifferenz;
     * die zählt jede Flanke doppelt und ergäbe 2f. Sein eigener Eichwert
     * lautet aber "bei einem Einzelton ist ZCR ~ f", also wird hier die
     * einfache Flankenzahl gezählt.
     *
     * Bei Summenmaterial ist die ZCR ohnehin fast deckungsgleich mit dem
     * Schwerpunkt und taugt nur als Percussion-Gate.
     */
    fun zcr(sampleRate: Int, x: FloatArray, n: Int = x.size): Double {
        if (n < 2) return 0.0
        var crossings = 0
        var previousNegative = x[0] < 0f
        for (i in 1 until n) {
            val negative = x[i] < 0f
            if (negative != previousNegative) crossings++
            previousNegative = negative
        }
        return sampleRate.toDouble() / (2.0 * n) * crossings
    }

    /** Effektivwert des Fensters, linear. */
    fun rms(x: FloatArray, n: Int = x.size): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = x[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n)
    }

    /**
     * Pegel in dBFS, mit der Hann-Korrektur von +4,26 dB.
     *
     * Das Hann-Fenster senkt den RMS um sqrt(3/8) = 0,6124, also 4,26 dB.
     * Ohne den Summanden wäre jede Anzeige zu leise und die Stille-Schwelle
     * bei -50 dBFS würde zu spät greifen.
     */
    fun rmsDb(x: FloatArray, n: Int = x.size): Double {
        val value = rms(x, n)
        // Ein exakt stilles Fenster hat log10(0) = -inf; das ist als Pegel
        // richtig und läuft durch jeden Vergleich korrekt weiter.
        return 20.0 * log10(value) + 4.26
    }
}
