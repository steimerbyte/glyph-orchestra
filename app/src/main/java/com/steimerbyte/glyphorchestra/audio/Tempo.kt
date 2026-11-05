package com.steimerbyte.glyphorchestra.audio

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ergebnis der Tempostufe.
 *
 * [bpmAlt] ist die Oktav-Alternative (×2 oder ÷2), sofern sie im Bereich
 * 60..180 liegt; sonst 0. Die Ambiguität wird nicht "gelöst", sondern
 * mitgeliefert -- bei einem 174-BPM-Track ist 87 genauso richtig (Bericht §3).
 * [meter] ist 3 oder 4, bei fehlenden Beats 0; [meterConfidence] kommt aus
 * dem Permutationstest und ist bewusst nicht in [confidence] eingemischt:
 * Taktart ist eine andere Frage als Beat-Existenz (Bericht §5.2).
 */
class TempoResult(
    val bpm: Double,
    val bpmAlt: Double,
    /** Beat-Zeiten in Sekunden, aufsteigend, auf 1 ms gerundet. */
    val beats: DoubleArray,
    val meter: Int,
    val meterConfidence: Double,
    /** Downbeat-Position in der Beatfolge, 0 = erster Beat ist die Eins. */
    val phase: Int,
    /** 0,4·Tempo + 0,3·Regelmäßigkeit + 0,3·Onset-Stärke. */
    val confidence: Double,
) {
    fun meterLabel(): String = when (meter) {
        3 -> "3/4"
        4 -> "4/4"
        else -> "?"
    }
}

/**
 * Tempo, Beatraster und Taktart aus der Onset-Hüllkurve.
 *
 * Reihenfolge wie im Bericht §7: ACF-Tempogramm mit Kamm und Prior, dann
 * DP-Beat-Tracking nach Ellis, dann Taktart aus den Tiefton-Akzenten.
 */
class TempoEstimator {

    fun estimate(env: OnsetEnvelope): TempoResult {
        val n = env.size
        if (n <= LAG_MAX) return none()

        val r = autocorrelation(env.acf, LAG_MAX)
        val scores = combScores(r, env.fps)
        val peakIndex = indexOfMax(scores)
        val rawBpm = refineBpm(scores, peakIndex)

        // Signifikanz statt Amplitude: unter weissem Rauschen ist E[r] = 0 und
        // Var[r] = 1/(T-tau), also ist z = r*sqrt(T-tau) über alle Lags
        // vergleichbar. Das Maximum von z über 260 Lags liegt dann bei ~3,5;
        // z < 5 heißt: es gibt keine belegte Periodizität (Bericht §2.1).
        val tau = 60.0 * env.fps / rawBpm
        if (acfAt(r, tau) * sqrt(n - tau) < Z_MIN) return none()

        val (bpm, beats) = chooseLevel(env, scores, rawBpm)
        if (beats.size < 2) return none()

        val tracked = refineTrack(env, beats, 60.0 * env.fps / bpm)
        if (tracked.size < 2) return none()

        val scatter = ibiScatter(tracked)
        val cTempo = tempoConfidence(scores, bpm)
        val cReg = 1.0 - min(1.0, scatter / 0.05)
        val cOnset = onsetConfidence(env.acf, env.fps, tracked)
        val confidence = 0.4 * cTempo + 0.3 * cReg + 0.3 * cOnset

        // Unter 0,3 ist das DP-Ergebnis ein Raster ohne Beleg; es auszugeben
        // hieße, die Glyphen auf einen zufälligen Takt blitzen zu lassen.
        if (confidence < CONFIDENCE_MIN) return none(confidence)

        val (meter, meterConfidence, phase) = meterOf(env, tracked)
        return TempoResult(
            bpm = bpm,
            bpmAlt = alternative(bpm),
            beats = beatTimes(env, tracked),
            meter = meter,
            meterConfidence = meterConfidence,
            phase = phase,
            confidence = confidence,
        )
    }

    /** Wählt zwischen τ und 2τ (bzw. τ/2), wenn beide Scores vergleichbar sind. */
    private fun chooseLevel(
        env: OnsetEnvelope,
        scores: DoubleArray,
        rawBpm: Double,
    ): Pair<Double, IntArray> {
        val other = if (rawBpm * 2.0 <= BPM_MAX) rawBpm * 2.0 else rawBpm / 2.0
        if (other < BPM_MIN || !ambiguous(scores, rawBpm, other)) {
            return rawBpm to beatTrack(env.dp, constant(env.size, 60.0 * env.fps / rawBpm))
        }

        // Nur bei vergleichbaren Scores ist die Entscheidung ein Münzwurf.
        // Bei einer 120-BPM-Walzer-Akzentfolge liegt 60 etwa halb so hoch --
        // dort würde ein blindes Verdoppeln das Tempo halbieren.
        val fast = max(rawBpm, other)
        val slow = min(rawBpm, other)
        val fastBeats = beatTrack(env.dp, constant(env.size, 60.0 * env.fps / fast))
        val alternation = alternation(accents(env, fastBeats))

        if (alternation > ALTERNATION_STRONG) {
            return slow to beatTrack(env.dp, constant(env.size, 60.0 * env.fps / slow))
        }
        if (alternation >= ALTERNATION_WEAK) {
            val slowTrack = beatTrack(env.dp, constant(env.size, 60.0 * env.fps / slow))
            // Mittelwerte je Beat, nie Summen: bei doppelt so vielen Beats ist
            // die Summe fast doppelt so groß und das schnelle Niveau gewänne
            // immer (Bericht §3).
            if (meanAt(env.dp, fastBeats) > 1.3 * meanAt(env.dp, slowTrack)) {
                return fast to fastBeats
            }
            return slow to slowTrack
        }
        return fast to fastBeats
    }

    /** Zwei-Pass nur bei Streuung > 2 %; darunter ist ein starres Raster besser. */
    private fun refineTrack(env: OnsetEnvelope, beats: IntArray, period: Double): IntArray {
        if (beats.size < 4 || ibiScatter(beats) <= 0.02) return beats
        return beatTrack(env.dp, localPeriods(beats, period, env.size))
    }

    // ---------------------------------------------------------------- ACF

    /**
     * r(τ) = 1/(T-τ)·Σ x(t)·x(t+τ). Die Division durch (T-τ) ist der
     * erwartungstreue Schätzer; ohne sie wären 8-s-Fenster 12,5 % zu schnell
     * und 4-s-Fenster 25 % (Bericht §2.1). Direkt statt per FFT: 260 Lags
     * kosten 5,3 M MAC, die FFT spart bei 20 672 Frames nur Faktor 2 und
     * braucht 512 KB Puffer (Bericht §6) -- der Speicher entscheidet hier,
     * nicht die Zeit.
     */
    private fun autocorrelation(x: FloatArray, maxLag: Int): DoubleArray {
        val r = DoubleArray(maxLag + 2)
        val n = x.size
        for (k in 1..maxLag) {
            var sum = 0.0
            for (t in 0 until n - k) sum += x[t].toDouble() * x[t + k]
            r[k] = sum / (n - k)
        }
        return r
    }

    /** Lagrange-3 an fraktionalem Lag; ohne sie wäre die BPM-Auflösung +-2 %. */
    private fun acfAt(r: DoubleArray, lag: Double): Double {
        val k = lag.toInt()
        val d = lag - k
        if (k < 1 || k + 1 >= r.size) return 0.0
        val a = r[k - 1]
        val b = r[k]
        val c = r[k + 1]
        return 0.5 * a * d * (d - 1.0) + b * (1.0 - d * d) + 0.5 * c * d * (d + 1.0)
    }

    /**
     * Kammfilter-Energie als ACF-Summe: S(τ) = 3r(τ) + 2r(2τ) + r(3τ) für
     * M = 4 Taps, dazu der perzeptuelle Prior um 120 BPM. Kein Resonator je
     * Kandidat, eine ACF plus 481·3 Interpolationen (Bericht §2.3).
     *
     * Halb- und Doppeltempo falten sich von selbst: der Kamm bewertet alle
     * Vielfachen von τ, ein 240-BPM-Track wird also über 120 gefunden, ohne
     * dass 240 je im Raster liegt (Bericht §3).
     */
    private fun combScores(r: DoubleArray, fps: Double): DoubleArray {
        val count = ((BPM_MAX - BPM_MIN) / BPM_STEP).roundToInt() + 1
        val out = DoubleArray(count)
        for (i in 0 until count) {
            val bpm = BPM_MIN + BPM_STEP * i
            val tau = 60.0 * fps / bpm
            var score = 0.0
            for (d in 1 until COMB_TAPS) score += (COMB_TAPS - d) * acfAt(r, d * tau)
            val octaves = log2(bpm / 120.0) / SIGMA_OCT
            out[i] = score * exp(-0.5 * octaves * octaves)
        }
        return out
    }

    private fun refineBpm(scores: DoubleArray, index: Int): Double {
        var delta = 0.0
        if (index > 0 && index < scores.size - 1) {
            val denominator = scores[index - 1] - 2 * scores[index] + scores[index + 1]
            if (abs(denominator) > 1e-12) {
                delta = (0.5 * (scores[index - 1] - scores[index + 1]) / denominator)
                    .coerceIn(-0.5, 0.5)
            }
        }
        return BPM_MIN + BPM_STEP * (index + delta)
    }

    private fun indexOfMax(values: DoubleArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    private fun scoreAt(scores: DoubleArray, bpm: Double): Double {
        // Am oberen Rand (bpm = 180) muss der letzte Rasterwert herauskommen
        // und nicht 0; sonst fällt die Konfidenz genau dort auf null.
        val x = ((bpm - BPM_MIN) / BPM_STEP).coerceIn(0.0, (scores.size - 1).toDouble())
        val i = min(x.toInt(), scores.size - 2)
        val d = x - i
        return scores[i] * (1.0 - d) + scores[i + 1] * d
    }

    private fun ambiguous(scores: DoubleArray, bpm: Double, other: Double): Boolean {
        val winner = scoreAt(scores, bpm)
        if (winner <= 0.0) return false
        return scoreAt(scores, other) >= AMBIGUITY_RATIO * winner
    }

    // ------------------------------------------------------- Beat-Tracking

    /**
     * DP nach Ellis (2007): Zustand ist nur t, die Periode ist
     * Entscheidungsvariable. C*(t) = O(t) + max_p { α·F(p, τ(t)) + C*(t-p) }
     * mit F = -(ln(p/τ))²; back[t] merkt sich den Vorgänger. Das Ende wird
     * im letzten 2τ-Fenster gesucht, dann läuft der Backtrace bis -1.
     */
    private fun beatTrack(onset: FloatArray, periodAt: DoubleArray): IntArray {
        val n = onset.size
        if (n == 0) return IntArray(0)
        var mean = 0.0
        for (p in periodAt) mean += p
        mean /= n
        if (mean <= 1.0) return IntArray(0)

        val pMin = max(1, floor(mean / 2.0).toInt())
        val pMax = max(pMin + 1, (2.0 * mean).roundToInt())
        val logP = DoubleArray(pMax + 1)
        for (p in pMin..pMax) logP[p] = ln(p.toDouble())

        val cumulative = FloatArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            val logPeriod = ln(max(periodAt[t], 1.0))
            var best = -1e30f
            var arg = -1
            for (p in pMin..min(pMax, t)) {
                val deviation = logP[p] - logPeriod
                val score = cumulative[t - p] + (-ALPHA * deviation * deviation).toFloat()
                if (score > best) {
                    best = score
                    arg = t - p
                }
            }
            // Start frei heißt: eine Kette darf an JEDEM Frame neu beginnen
            // (Kosten 0), nicht nur solange es keinen Vorgänger gibt. Der
            // Pseudocode im Bericht erlaubt den Start nur für t < tau/2;
            // dann hängt der letzte freie Frame vor tau/2 an der Kette und
            // zieht den ersten Beat bis zu tau/2 Frames nach vorn -- bei
            // 120 BPM 240 ms, also weit außerhalb jeder Toleranz.
            if (best > 0f) {
                cumulative[t] = onset[t] + best
                back[t] = arg
            } else {
                cumulative[t] = onset[t]
                back[t] = -1
            }
        }

        var end = n - 1
        for (t in max(0, n - pMax) until n) if (cumulative[t] > cumulative[end]) end = t
        var count = 0
        var i = end
        while (i >= 0) {
            count++
            i = back[i]
        }
        val beats = IntArray(count)
        i = end
        var k = count - 1
        while (i >= 0) {
            beats[k--] = i
            i = back[i]
        }
        return beats
    }

    private fun constant(n: Int, period: Double): DoubleArray = DoubleArray(n) { period }

    /**
     * τ_loc(t) als Median der IBI über die acht Beats um t, stückweise
     * zwischen den Beats. Die Suchbreite bleibt die globale (τ/2..2τ), weil
     * die lokale Periode nur um wenige Prozent wandert.
     */
    private fun localPeriods(beats: IntArray, fallback: Double, n: Int): DoubleArray {
        val at = DoubleArray(n) { fallback }
        if (beats.size < 2) return at
        val intervals = DoubleArray(beats.size - 1) { (beats[it + 1] - beats[it]).toDouble() }
        for (j in beats.indices) {
            val from = max(0, j - 4)
            val to = min(intervals.size, j + 4)
            val local = if (to > from) median(intervals.copyOfRange(from, to)) else fallback
            val start = beats[j]
            val end = if (j + 1 < beats.size) beats[j + 1] else n
            for (t in start until min(end, n)) at[t] = local
        }
        return at
    }

    private fun ibiScatter(beats: IntArray): Double {
        if (beats.size < 3) return 0.0
        val mean = (beats.last() - beats.first()).toDouble() / (beats.size - 1)
        if (mean <= 0.0) return 0.0
        var squares = 0.0
        for (i in 1 until beats.size) {
            val d = (beats[i] - beats[i - 1]) - mean
            squares += d * d
        }
        return sqrt(squares / (beats.size - 1)) / mean
    }

    private fun meanAt(values: FloatArray, frames: IntArray): Double {
        if (frames.isEmpty()) return 0.0
        var sum = 0.0
        for (frame in frames) if (frame in values.indices) sum += values[frame]
        return sum / frames.size
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        sorted.sort()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) 0.5 * (sorted[middle - 1] + sorted[middle]) else sorted[middle]
    }

    // ------------------------------------------------------------- Ausgabe

    /**
     * Die DP-Schritte liegen auf dem Framegitter (11,6 ms). Die Parabel auf
     * dem Flux-Scheitel verschiebt den Beat um höchstens einen halben Frame
     * und nimmt einen Teil des Vorlaufs heraus, den das 46-ms-Fenster einem
     * Impuls gibt. Für die Anzeige wäre das irrelevant (20-ms-Ticker), für
     * die Beat-Zeit im Ergebnis nicht.
     */
    private fun beatTimes(env: OnsetEnvelope, beats: IntArray): DoubleArray =
        DoubleArray(beats.size) { j ->
            val frame = refineFrame(env.dp, beats[j])
            round(frameCenterSec(frame, env.fps, env.centerFrames) * 1000.0) / 1000.0
        }

    private fun refineFrame(onset: FloatArray, frame: Int): Double {
        val a = if (frame > 0) onset[frame - 1].toDouble() else 0.0
        val b = onset[frame].toDouble()
        val c = if (frame + 1 < onset.size) onset[frame + 1].toDouble() else 0.0
        val denominator = a - 2 * b + c
        if (abs(denominator) < 1e-12) return frame.toDouble()
        return frame + (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
    }

    private fun alternative(bpm: Double): Double {
        if (bpm * 2.0 <= BPM_MAX) return bpm * 2.0
        val half = bpm / 2.0
        return if (half >= BPM_MIN) half else 0.0
    }

    private fun none(confidence: Double = 0.0) =
        TempoResult(0.0, 0.0, DoubleArray(0), 0, 0.0, 0, confidence)

    // ---------------------------------------------------------- Konfidenzen

    /**
     * c_tempo = 1 - S2/S1, wobei S2 der beste Peak ist, dessen Periode nicht
     * in [0,95k·τ, 1,05k·τ] für k = 1..4 liegt. Ohne diesen Ausschluss hätte
     * ein perfekter Klicktrack c = 0, weil die harmonischen Peaks gleich hoch
     * sind (Bericht §5.2).
     */
    private fun tempoConfidence(scores: DoubleArray, bpm: Double): Double {
        val winner = scoreAt(scores, bpm)
        if (winner <= 0.0) return 0.0
        var second = Double.NEGATIVE_INFINITY
        for (i in 1 until scores.size - 1) {
            if (scores[i] < scores[i - 1] || scores[i] < scores[i + 1]) continue
            if (related(BPM_MIN + BPM_STEP * i, bpm)) continue
            if (scores[i] > second) second = scores[i]
        }
        if (second == Double.NEGATIVE_INFINITY) return 1.0
        return (1.0 - second / winner).coerceIn(0.0, 1.0)
    }

    private fun related(candidate: Double, bpm: Double): Boolean {
        for (k in 1..4) {
            val target = bpm / k
            if (abs(candidate - target) <= 0.05 * target) return true
        }
        return false
    }

    /** c_i = clip((x - mu_loc)/(3·sigma_loc), 0, 1) über +-2 s. */
    private fun onsetConfidence(x: FloatArray, fps: Double, beats: IntArray): Double {
        if (beats.isEmpty()) return 0.0
        val prefix = DoubleArray(x.size + 1)
        val squares = DoubleArray(x.size + 1)
        for (i in x.indices) {
            prefix[i + 1] = prefix[i] + x[i]
            squares[i + 1] = squares[i] + x[i].toDouble() * x[i]
        }
        val radius = (2.0 * fps).roundToInt()
        var strong = 0
        for (beat in beats) {
            if (beat !in x.indices) continue
            val from = max(0, beat - radius)
            val to = min(x.size, beat + radius + 1)
            val count = to - from
            if (count <= 0) continue
            val mean = (prefix[to] - prefix[from]) / count
            val variance = max(0.0, (squares[to] - squares[from]) / count - mean * mean)
            val sd = sqrt(variance)
            if (sd < 1e-9) continue
            if ((x[beat] - mean) / (3.0 * sd) > 0.5) strong++
        }
        return strong.toDouble() / beats.size
    }

    // -------------------------------------------------------------- Taktart

    private fun accents(env: OnsetEnvelope, beats: IntArray): DoubleArray {
        if (beats.isEmpty()) return DoubleArray(0)
        val meanPeriod =
            if (beats.size > 1) (beats.last() - beats.first()).toDouble() / (beats.size - 1) else 0.0
        return DoubleArray(beats.size) { j ->
            val from = beats[j]
            val to = if (j + 1 < beats.size) beats[j + 1] else from + max(1, meanPeriod.roundToInt())
            var sum = 0.0
            var count = 0
            for (t in from until min(to, env.low.size)) {
                sum += env.low[t]
                count++
            }
            if (count == 0) env.low.getOrElse(from) { 0f }.toDouble() else sum / count
        }
    }

    /**
     * A = |μ_ungerade - μ_gerade| / σ_gepoolt auf den rohen Akzenten. Die
     * Robustisierung aus §4 gehört nicht hierher: sie macht aus einem
     * Vierergruppen-Muster (drei gleiche Werte) eine Nullreihe und würde
     * genau den Fall verschlucken, den A erkennen soll.
     */
    private fun alternation(accents: DoubleArray): Double {
        if (accents.size < 4) return 0.0
        var oddSum = 0.0
        var oddSquares = 0.0
        var oddCount = 0
        var evenSum = 0.0
        var evenSquares = 0.0
        var evenCount = 0
        for (i in accents.indices) {
            if (i % 2 == 0) {
                evenSum += accents[i]
                evenSquares += accents[i] * accents[i]
                evenCount++
            } else {
                oddSum += accents[i]
                oddSquares += accents[i] * accents[i]
                oddCount++
            }
        }
        if (oddCount == 0 || evenCount == 0) return 0.0
        val oddMean = oddSum / oddCount
        val evenMean = evenSum / evenCount
        val oddVar = max(0.0, oddSquares / oddCount - oddMean * oddMean)
        val evenVar = max(0.0, evenSquares / evenCount - evenMean * evenMean)
        val pooled = sqrt((oddVar + evenVar) / 2)
        if (pooled < 1e-9) return if (abs(oddMean - evenMean) < 1e-9) 0.0 else 99.0
        return abs(oddMean - evenMean) / pooled
    }

    /**
     * â = (a - median)/(1,4826·MAD), |â| > 5 wird zum Median gezogen.
     *
     * Sonderfall MAD = 0: dann ist mehr als die Hälfte der Akzente gleich
     * (Walzer: zwei von drei), es gibt also keine Ausreißer zu entfernen.
     * Ohne den Rückfall auf die Standardabweichung wäre die ganze Reihe
     * null und eine 3/4-Erkennung unmöglich.
     */
    private fun robust(accents: DoubleArray): DoubleArray {
        if (accents.isEmpty()) return accents
        val centre = median(accents)
        val deviations = DoubleArray(accents.size) { abs(accents[it] - centre) }
        val mad = median(deviations)
        if (mad < 1e-9) return standardised(accents)
        val scale = 1.4826 * mad
        return DoubleArray(accents.size) {
            val z = (accents[it] - centre) / scale
            if (abs(z) > 5.0) 0.0 else z
        }
    }

    private fun standardised(values: DoubleArray): DoubleArray {
        val mean = values.average()
        var squares = 0.0
        for (v in values) {
            val d = v - mean
            squares += d * d
        }
        val sd = sqrt(squares / values.size)
        if (sd < 1e-12) return DoubleArray(values.size)
        return DoubleArray(values.size) { (values[it] - mean) / sd }
    }

    /** ρ(B) auf Beat-Einheiten; skalenfrei, deshalb zwischen B vergleichbar. */
    private fun periodicity(values: DoubleArray, lag: Int): Double {
        if (values.size <= lag) return 0.0
        val mean = values.average()
        var denominator = 0.0
        for (v in values) {
            val d = v - mean
            denominator += d * d
        }
        if (denominator < 1e-12) return 0.0
        var numerator = 0.0
        for (i in 0 until values.size - lag) {
            numerator += (values[i] - mean) * (values[i + lag] - mean)
        }
        return numerator / denominator
    }

    private fun phaseOf(values: DoubleArray, meter: Int): Int {
        if (values.size < meter) return 0
        var best = 0
        var bestMean = Double.NEGATIVE_INFINITY
        for (phase in 0 until meter) {
            var sum = 0.0
            var count = 0
            for (i in phase until values.size step meter) {
                sum += values[i]
                count++
            }
            if (count > 0 && sum / count > bestMean) {
                bestMean = sum / count
                best = phase
            }
        }
        return best
    }

    /**
     * Taktart per Permutationstest: 400 Fisher-Yates-Mischungen, p ist der
     * Anteil mit |ρ4 - ρ3| >= beobachtet. Kein Verteilungsmodell, also kein
     * Hand-Waving; bei N = 480 Beats sind das 384 k Operationen (Bericht §4).
     *
     * Der Niveau-Guard aus §4 (ρ(2B) > ρ(B) + 2σ → Niveau halbieren) fehlt:
     * er verlangt einen zweiten kompletten DP auf halber Rate. Den Fall, den
     * er fängt -- ein akzentuiertes Achtelraster -- entscheidet schon
     * [chooseLevel]: dort halbiert der Alternationstest das Niveau, sobald
     * die Unterteilung hörbar alterniert. Ein akzentfreies Achtelraster hat
     * keine Taktart-Information, dann bleibt es bei 4/4 mit niedriger
     * Konfidenz. Die verbleibende Lücke ist ein gleichmäßiges, aber
     * betontes Achtelraster, dessen Betonung der Alternationstest nicht sieht.
     */
    private fun meterOf(env: OnsetEnvelope, beats: IntArray): Triple<Int, Double, Int> {
        val accents = robust(accents(env, beats))
        if (accents.size < 8) return Triple(4, 0.0, 0)
        val rho3 = periodicity(accents, 3)
        val rho4 = periodicity(accents, 4)
        val observed = abs(rho4 - rho3)
        val best = if (rho4 >= rho3) 4 else 3

        val random = Random(PERMUTATION_SEED)
        val work = accents.copyOf()
        var hits = 0
        repeat(PERMUTATIONS) {
            for (i in work.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val swap = work[i]
                work[i] = work[j]
                work[j] = swap
            }
            if (abs(periodicity(work, 4) - periodicity(work, 3)) >= observed) hits++
        }
        val confidence = 1.0 - hits.toDouble() / PERMUTATIONS

        // Ohne belastbaren Unterschied bleibt 4/4 stehen; eine 3/4-Meldung mit
        // Münzwurf-Konfidenz wäre eine Scheinsicherheit (Bericht §4, Grenze 1).
        val meter = if (best == 3 && confidence >= METER_CONFIDENCE_MIN) 3 else 4
        return Triple(meter, confidence, phaseOf(accents, meter))
    }
}

private const val BPM_MIN = 60.0
private const val BPM_MAX = 180.0

/** 0,25 BPM Raster; danach Lagrange auf ~0,02 BPM. */
private const val BPM_STEP = 0.25

/** Ellis' getunter Prior, 0,9 Oktaven um 120 BPM. */
private const val SIGMA_OCT = 0.9

/** M = 4 Taps, Gewichte 3/2/1. */
private const val COMB_TAPS = 4

/** 3·τ(60) + 2 = 260: die dritte Harmonische bei 60 BPM. */
private const val LAG_MAX = 260

/** ±10 % Tempoabweichung kostet ~3,6 σ, ±20 % bereits 13,3 σ. */
private const val ALPHA = 400.0

/** Unter dem Rauschmaximum (~3,5) plus Reserve. */
private const val Z_MIN = 5.0

/** Unter dieser Gesamtkonfidenz gibt es keine Beats, nur ein Raster. */
private const val CONFIDENCE_MIN = 0.3

private const val ALTERNATION_STRONG = 1.0
private const val ALTERNATION_WEAK = 0.5

/** Unter 15 % Score-Abstand ist das Niveau ein Münzwurf. */
private const val AMBIGUITY_RATIO = 0.85

private const val PERMUTATIONS = 400
private const val PERMUTATION_SEED = 20261105L
private const val METER_CONFIDENCE_MIN = 0.6
