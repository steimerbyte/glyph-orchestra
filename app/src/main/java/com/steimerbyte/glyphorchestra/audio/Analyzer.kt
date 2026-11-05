package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.DeviceSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ein Durchlauf von der Tondatei bis zu Bildern für die Glyphen.
 *
 * Warum in zwei Runden: die Bänder brauchen ihre eigenen Perzentile als
 * Bezug, und die stehen erst fest, wenn der ganze Song gesehen ist. Runde 1
 * rechnet deshalb nur kennzahlenarme Werte (17 Bänder plus vier Skalare je
 * Frame), Runde 2 baut daraus Spannung und Helligkeiten. Die Leistungsspektren
 * selbst werden nicht behalten — ein 4-Minuten-Song hätte sonst 42 MB.
 */
object Analyzer {

    data class Progress(val stage: String, val done: Int, val total: Int)

    /** Billiges Rauheitsmaß: wie unruhig die Nachbarbins zueinander stehen. */
    private fun irregularity(power: FloatArray, from: Int, to: Int): Float {
        var diff = 0.0
        var sum = 0.0
        for (k in from + 1..to) {
            val a = power[k].toDouble()
            val b = power[k - 1].toDouble()
            diff += abs(a - b)
            sum += a
        }
        return if (sum <= 1e-12) 0f else (diff / sum).toFloat()
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val pos = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val i = pos.toInt()
        val f = (pos - i).toFloat()
        return if (i + 1 < sorted.size) sorted[i] * (1 - f) + sorted[i + 1] * f else sorted[i]
    }

    fun analyze(
        source: PcmSource,
        spec: DeviceSpec,
        onProgress: (Progress) -> Unit = {},
    ): AnalysisResult {
        val spectrum = Spectrum(source.sampleRate)
        val n = spectrum.n
        val hop = Dsp.HOP
        val fpsAnalysis = Dsp.analysisFps(source.sampleRate)
        // Fenstermitte in Frames: der Analyseframe i beschreibt die Zeit
        // (i + centerFrames)/fps, nicht i/fps.
        val centerFrames = n / (2.0 * hop)
        val onset = OnsetDetector(
            fps = fpsAnalysis,
            binHz = source.sampleRate.toDouble() / n,
            centerFrames = centerFrames,
        )

        // Runde 1: Frames zaehlen und Kennzahlen sammeln.
        val bands = ArrayList<FloatArray>(1 shl 14)
        val centroids = ArrayList<Float>()
        val louds = ArrayList<Float>()
        val roughs = ArrayList<Float>()

        val frame = FloatArray(n)
        val chunk = FloatArray(hop)
        var total = 0
        var read = source.read(frame)
        if (read < n) frame.fill(0f, max(read, 0), n)
        var eof = read < 0
        while (!eof) {
            spectrum.analyze(frame, 0)
            onset.addFrame(spectrum.power)
            bands.add(spectrum.bandsDb.copyOf())
            centroids.add(
                Features.centroid(spectrum.power, source.sampleRate.toDouble() / n).toFloat(),
            )
            louds.add(Features.rmsDb(frame).toFloat())
            roughs.add(irregularity(spectrum.power, 5, spectrum.bins - 1))
            total++

            System.arraycopy(frame, hop, frame, 0, n - hop)
            var got = source.read(chunk)
            if (got < 0) {
                chunk.fill(0f)
                got = 0
                eof = true
            } else if (got < hop) {
                chunk.fill(0f, got, hop)
                eof = true
            }
            System.arraycopy(chunk, 0, frame, n - hop, hop)
            if (total % 512 == 0) onProgress(Progress("Analysiere", total, 0))
        }

        val frames = total
        // Eine leere oder zu kurze Datei hat kein einziges Fenster. Ein Bild
        // mit lauter Nullen ist die ehrliche Antwort; ohne diesen Zweig
        // stuerzt die Skalierung auf die Anzeigerate an einem leeren Bereich.
        if (frames == 0) {
            return AnalysisResult(
                fpsDraw = Dsp.FPS_DRAW,
                frames = 1,
                levels = IntArray(36),
                tension = FloatArray(1),
                bandsDb = FloatArray(17),
                fpsAnalysis = fpsAnalysis,
                bpm = 0.0,
                beats = DoubleArray(0),
                meter = "?",
                confidence = 0.0,
                durationSec = 0.0,
            )
        }
        val env = onset.finish()
        val tempo = TempoEstimator().estimate(env)
        onProgress(Progress("Tempo", frames, frames))

        // Novelty ist die Timbre-Änderung der Bänder, nicht der Onset-Fluss:
        // aus dem Fluss würde jeder Kick einen Spannungspeak (Bericht §4.1).
        val noveltyDetector = TimbreNovelty(fpsAnalysis)
        val novelties = FloatArray(frames)
        for (i in 0 until frames) novelties[i] = noveltyDetector.push(bands[i])

        // Referenzen aus dem ganzen Song.
        val bandP95 = FloatArray(BandToChannel.BAND_COUNT.let { 17 })
        val bandP10 = FloatArray(17)
        for (b in 0 until 17) {
            val col = FloatArray(frames) { bands[it][b] }
            bandP95[b] = percentile(col, 0.95)
            bandP10[b] = percentile(col, 0.10)
        }
        val centroidLog = FloatArray(frames) { log2(max(centroids[it], 20f)).toFloat() }
        val ranges = TensionRanges(
            loudP95Db = percentile(louds.toFloatArray(), 0.95),
            centroidP5Log2 = percentile(centroidLog, 0.05),
            centroidP95Log2 = percentile(centroidLog, 0.95),
            roughnessP5 = percentile(roughs.toFloatArray(), 0.05),
            roughnessP95 = percentile(roughs.toFloatArray(), 0.95),
            noveltyP5 = percentile(novelties, 0.05),
            noveltyP95 = percentile(novelties, 0.95),
        )

        // Runde 2: Spannung und Helligkeiten je Analyseframe.
        val tension = Tension(ranges)
        val mapper = BandToChannel(spec, fpsAnalysis, bandP95, bandP10)
        val dt = 1.0 / fpsAnalysis
        val analysisLevels = IntArray(frames * 36)
        val row = IntArray(36)
        val tensionPerFrame = FloatArray(frames)
        for (i in 0 until frames) {
            val t = tension.push(
                loudDb = louds[i],
                centroidHz = centroids[i],
                roughness = roughs[i],
                novelty = novelties[i],
                dtSec = dt,
            )
            tensionPerFrame[i] = t
            mapper.map(bands[i], t, row)
            System.arraycopy(row, 0, analysisLevels, i * 36, 36)
            if (i % 1024 == 0) onProgress(Progress("Zeichne", i, frames))
        }

        // Auf die Anzeigerate bringen: 86,1 auf 30 Bilder/s.
        val durationSec = frames / fpsAnalysis
        val drawFrames = ceil(durationSec * Dsp.FPS_DRAW).toInt().coerceAtLeast(1)
        val levels = IntArray(drawFrames * 36)
        val drawTension = FloatArray(drawFrames)
        for (d in 0 until drawFrames) {
            // Anzeigeframe d steht für die Zeit d/30. Der Analyseframe, dessen
            // Fenstermitte dort liegt, ist d·fps/30 − N/(2·HOP). Ohne den Abzug
            // läuft die Bildfolge zwei Analyseframes (23,2 ms) vor den
            // Beatzeiten her -- mehr als die halbe Toleranz einer Beat-Zeit.
            val a = (d.toDouble() * fpsAnalysis / Dsp.FPS_DRAW - centerFrames)
                .roundToInt()
                .coerceIn(0, frames - 1)
            System.arraycopy(analysisLevels, a * 36, levels, d * 36, 36)
        }
        // Die Gesamthelligkeit hängt am Beat-Raster der erkannten Beats; ohne
        // Beats setzt quantize nur auf die Anzeigerate um (Bericht §4.4).
        Tension.quantize(
            curve = tensionPerFrame,
            curveFps = fpsAnalysis,
            beats = tempo.beats,
            drawFps = Dsp.FPS_DRAW,
            frames = drawFrames,
            out = drawTension,
        )

        val flatBands = FloatArray(frames * 17)
        for (i in 0 until frames) System.arraycopy(bands[i], 0, flatBands, i * 17, 17)

        onProgress(Progress("Fertig", drawFrames, drawFrames))
        return AnalysisResult(
            fpsDraw = Dsp.FPS_DRAW,
            frames = drawFrames,
            levels = levels,
            tension = drawTension,
            bandsDb = flatBands,
            fpsAnalysis = fpsAnalysis,
            bpm = tempo.bpm,
            beats = tempo.beats,
            meter = tempo.meterLabel(),
            confidence = tempo.confidence,
            durationSec = durationSec,
        )
    }
}

/**
 * Timbre-Novelty 0..1: Kosinusdistanz der dB-Form zum Vorframe (Bericht §4.1).
 *
 * Warum nicht der Onset-Fluss: der steigt bei jedem Kick, und dann bekäme
 * jeder Schlag einen Spannungspeak. Die dB-Form — jedes Band minus das
 * lauteste Band — ist lautheitsunabhängig; sie ändert sich erst, wenn sich das
 * Instrumentarium verschiebt: Strophe zu Refrain, Filter auf, Becken dazu.
 *
 * Ohne Allokation je Frame: die Form des Vorframes steht in [shape] und wird
 * in place überschrieben.
 */
class TimbreNovelty(fps: Double, tauSec: Double = 0.2) {

    private val shape = FloatArray(BandToChannel.BAND_COUNT)

    /** Gewicht des neuen Frames: 1 − exp(−Δt/0,2 s), bei 86,1 fps 0,056. */
    private val alpha = 1.0 - exp(-1.0 / (fps * tauSec))

    private var value = 0f

    /** Der erste Frame hat keinen Vorgänger; er darf keinen Sprung erfinden. */
    private var primed = false

    fun push(bandsDb: FloatArray): Float {
        val count = BandToChannel.BAND_COUNT
        var loudest = bandsDb[0]
        for (b in 1 until count) if (bandsDb[b] > loudest) loudest = bandsDb[b]

        var dot = 0.0
        var norm = 0.0
        var prevNorm = 0.0
        for (b in 0 until count) {
            val a = bandsDb[b] - loudest
            dot += a * shape[b]
            norm += a * a
            prevNorm += shape[b] * shape[b]
        }

        // Zwei stille Frames haben keine Richtung; dann gibt es keine Änderung.
        val raw = if (primed && norm > 1e-12 && prevNorm > 1e-12) {
            (1.0 - dot / sqrt(norm * prevNorm)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        for (b in 0 until count) shape[b] = bandsDb[b] - loudest
        primed = true
        value = (value + alpha * (raw - value)).toFloat()
        return value
    }
}
