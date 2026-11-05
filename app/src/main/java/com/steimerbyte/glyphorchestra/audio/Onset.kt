package com.steimerbyte.glyphorchestra.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Zeit der Fenstermitte eines Analyseframes in Sekunden.
 *
 * Frame i beginnt bei Sample i*[Dsp.HOP], das Spektrum beschreibt aber die
 * Fenstermitte: t = (i*HOP + N/2)/fs. Mit fps = fs/HOP ist das
 * (i + N/(2*HOP))/fps = (i + 2)/fps. Wer i*HOP/fs nimmt, legt jeden Beat
 * 23,2 ms zu früh (Bericht §0) -- das ist mehr als die halbe Toleranz, die
 * eine Beat-Zeit überhaupt hat.
 */
fun frameCenterSec(
    frame: Double,
    fps: Double,
    centerFrames: Double = Dsp.N / (2.0 * Dsp.HOP),
): Double = (frame + centerFrames) / fps

/**
 * Onset-Hüllkurven eines ganzen Tracks.
 *
 * [dp] ist nicht negativ und hat die Streuung 1. Der DP darf keine Nullmitte
 * sehen: bei nullmittigem O kostet jeder ausgelassene Beat den Mittelwert,
 * und der Tracker dünnt die Beats systematisch aus.
 *
 * [acf] ist nullmittig und hat die Streuung 1. Die Autokorrelation braucht
 * E[x] = 0, sonst dominiert der Term mu^2*(T-tau) und verzerrt kurze Lags
 * nach oben. Zwei Reihen aus einer Quelle, weil die beiden Anforderungen
 * einander ausschließen (Bericht §1.1, §2.1).
 *
 * [low] ist der Flux der Bänder 1-4 (100-324 Hz), ebenfalls nicht negativ
 * und normiert; daraus kommen die Beat-Akzente für die Taktart, weil Kick
 * und Bass dort liegen und nicht im 100-Hz-Raster der 16 Bänder (Bericht
 * §4).
 */
class OnsetEnvelope(
    val fps: Double,
    val dp: FloatArray,
    val acf: FloatArray,
    val low: FloatArray,
    /** Fenstermitte in Frames: N/(2*HOP), abhaengig von der Fensterlaenge. */
    val centerFrames: Double = Dsp.N / (2.0 * Dsp.HOP),
) {
    val size: Int get() = dp.size

    fun timeSec(frame: Int): Double = frameCenterSec(frame.toDouble(), fps, centerFrames)
}

/**
 * SuperFlux-Onset-Hüllkurve nach Böck & Widmer, Zahlen aus Bericht §1.
 *
 * Der Detektor nimmt je Analyseframe ein Leistungsspektrum, in dem ein
 * Vollaussteuerungssinus im Bin die Leistung 1,0 hat. Er sammelt nur den
 * rohen Fluss; Mittelwert und Streuung gelten über den ganzen Track und
 * werden deshalb erst in [finish] gerechnet.
 */
class OnsetDetector(
    private val fps: Double,
    /**
     * Binbreite in Hz = sampleRate / Fensterlaenge. Sie kommt von aussen,
     * weil sie sich nicht aus fps ableiten laesst: bei 22,05 kHz ist das
     * Fenster 1024 lang (Spectrum waehlt es nach der Rate), der Hop bleibt
     * 512, und fps*HOP/N waere dann eine Oktave daneben.
     */
    private val binHz: Double = 44100.0 / Dsp.N,
    private val centerFrames: Double = Dsp.N / (2.0 * Dsp.HOP),
) {

    private val flux = ArrayList<Float>()
    private val lowFlux = ArrayList<Float>()
    private var previous: FloatArray? = null
    private var current: FloatArray? = null
    private var lowFrom = 1
    private var lowTo = 0
    private var frames = 0

    /**
     * Ein Frame, Bins 1..512 (21,5 Hz .. 11 kHz). Bin 0 (Gleichanteil) und
     * alles über 11 kHz bleiben draußen: der Gleichanteil ist kein Onset,
     * und oberhalb 11 kHz liegt bei 44,1 kHz nur noch Rauschen und Zischen.
     */
    fun addFrame(power: FloatArray) {
        if (power.size < 2) return
        val last = minOf(LAST_BIN, power.size - 1)

        if (previous == null) {
            previous = FloatArray(power.size)
            current = FloatArray(power.size)
            // Tieftonbereich aus der echten Binbreite der Rate.
            lowFrom = ceil(LOW_FROM_HZ / binHz).toInt().coerceAtLeast(1)
            lowTo = floor(LOW_TO_HZ / binHz).toInt().coerceAtMost(last)
        }
        val previousFrame = previous!!
        val log = current!!

        var high = 0f
        var low = 0f
        for (k in 1..last) {
            val magnitude = sqrt(power[k].coerceAtLeast(0f))
            log[k] = ln(1f + LAMBDA * magnitude)
            // Max-Filter über drei Bins: +-1 Bin ist bei 100 Hz ein Viertelton
            // Toleranz gegen Stimmung und Hann-Leakage, bei Perkussion ohne
            // Vibrato ausreichend (Bericht §1.1, Punkt 2).
            val below = previousFrame[if (k > 1) k - 1 else 1]
            val centre = previousFrame[k]
            val above = if (k < last) previousFrame[k + 1] else 0f
            val diff = log[k] - max(below, max(centre, above))
            if (diff > 0f) {
                high += diff
                if (k in lowFrom..lowTo) low += diff
            }
        }

        // Der erste Frame hat keinen Vorgänger; sein "Fluss" wäre die Summe
        // des ganzen Spektrums und kein Onset.
        flux.add(if (frames == 0) 0f else high)
        lowFlux.add(if (frames == 0) 0f else low)
        frames++

        previous = log
        current = previousFrame
    }

    /** Rechnet die Normalisierung und gibt beide Reihen heraus. */
    fun finish(): OnsetEnvelope {
        val window = (LOCAL_MEAN_SEC * fps).roundToInt().coerceAtLeast(1)
        val highPassed = removeLocalMean(flux.toFloatArray(), window)
        val dp = scale(highPassed)
        val acf = FloatArray(dp.size)
        if (dp.isNotEmpty()) {
            val mean = dp.average()
            for (i in dp.indices) acf[i] = (dp[i] - mean).toFloat()
        }
        return OnsetEnvelope(
            fps = fps,
            dp = dp,
            acf = acf,
            low = scale(removeLocalMean(lowFlux.toFloatArray(), window)),
            centerFrames = centerFrames,
        )
    }
}

/** Log-Kompression: 1000:1 Eingangsdynamik wird zu 10:1 (Bericht §1.1). */
private const val LAMBDA = 1000f

/** Bins 1..512 = 21,5 Hz .. 11 025 Hz bei 44,1 kHz. */
private const val LAST_BIN = 512

/** Gleitender Mittelwert über 1,5 s, entspricht ~0,67 Hz Hochpass. */
private const val LOCAL_MEAN_SEC = 1.5

/** Bänder 1-4 des Berichts: 100 Hz .. 324 Hz, Kick und Bass. */
private const val LOW_FROM_HZ = 100.0
private const val LOW_TO_HZ = 324.0

/**
 * Zieht den gleitenden Mittelwert ab und schneidet bei 0 ab.
 *
 * Die Ränder werden durch die tatsächlich vorhandene Fensterbreite geteilt
 * und nicht durch die volle W; sonst würden die ersten und letzten Frames
 * durch die Nullauffüllung angehoben und sähen wie Onsets aus.
 */
private fun removeLocalMean(x: FloatArray, window: Int): FloatArray {
    if (x.isEmpty()) return x
    val prefix = DoubleArray(x.size + 1)
    for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
    val left = window / 2
    val right = window - left
    return FloatArray(x.size) { n ->
        val from = max(0, n - left)
        val to = min(x.size, n + right)
        max(0.0, x[n] - (prefix[to] - prefix[from]) / (to - from)).toFloat()
    }
}

/**
 * Skaliert auf Streuung 1. Stille und ein konstantes Spektrum haben keine
 * Streuung; dann gibt es keine Onsets und die Reihe bleibt null.
 */
private fun scale(x: FloatArray): FloatArray {
    if (x.isEmpty()) return x
    var sum = 0.0
    for (v in x) sum += v
    val mean = sum / x.size
    var squares = 0.0
    for (v in x) {
        val d = v - mean
        squares += d * d
    }
    val sd = sqrt(squares / x.size)
    if (sd < 1e-9) return FloatArray(x.size)
    return FloatArray(x.size) { (x[it] / sd).toFloat() }
}
