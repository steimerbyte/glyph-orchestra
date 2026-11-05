package com.steimerbyte.glyphorchestra.audio

import java.util.Arrays
import kotlin.math.exp
import kotlin.math.log2

/**
 * Die Perzentile eines ganzen Songs, gegen die [Tension] seine vier Zutaten
 * normiert.
 *
 * Warum diese Werte von außen kommen: sie brauchen zwei Pässe über den
 * ganzen Song, die Spannungsrechnung selbst läuft frame-weise. Ein
 * streamender Schätzer würde die Helligkeit rückwirkend verschieben --
 * der Bericht verbietet das ausdrücklich.
 *
 * [centroidP5Log2]/[centroidP95Log2] sind log2(Hz), nicht Hz: die Normierung
 * läuft in Oktaven, damit die Spanne bei jeder Stimmlage gleich breit ist.
 */
data class TensionRanges(
    val loudP95Db: Float,
    val centroidP5Log2: Float,
    val centroidP95Log2: Float,
    val roughnessP5: Float,
    val roughnessP95: Float,
    val noveltyP5: Float,
    val noveltyP95: Float,
)

/**
 * Die Spannungskurve 0..1: vier normierte Zutaten, gewichtet und asymmetrisch
 * geglättet.
 *
 * Die Gewichte stammen aus dem Bericht: Lautheit ist der stärkste
 * Einzelprädiktor, der Schwerpunkt liefert die Richtung, Novelty trägt die
 * Abschnittswechsel (ohne sie wäre jeder Refrain konstant), Rauheit wiegt
 * bewusst nur 10 %, weil sie auf Beckendichte reagiert statt auf Harmonik.
 *
 * Die Glättung ist asymmetrisch, weil ein Drop sofort oben sein muss, ein
 * Abfall aber dem Höreindruck folgt -- mit einer einzigen Zeitkonstante
 * flackerte die Gesamthelligkeit bei jedem Übergang.
 */
class Tension(
    private val ranges: TensionRanges,
    private val riseTauSec: Double = 0.10,
    private val fallTauSec: Double = 0.40,
) {

    /** Der aktuelle geglättete Wert, 0..1. */
    var value: Float = 0f
        private set

    /**
     * Ein Analyseframe. [dtSec] ist der Abstand zum vorigen Frame; bei einer
     * Änderung der Abtastrate also nicht konstant 1/86,133 s.
     */
    fun push(
        loudDb: Float,
        centroidHz: Float,
        roughness: Float,
        novelty: Float,
        dtSec: Double,
    ): Float {
        val target = raw(
            loud(loudDb.toDouble(), ranges.loudP95Db.toDouble()),
            centroidNorm(
                centroidHz.toDouble(),
                ranges.centroidP5Log2.toDouble(),
                ranges.centroidP95Log2.toDouble(),
            ),
            rangeNorm(
                roughness.toDouble(),
                ranges.roughnessP5.toDouble(),
                ranges.roughnessP95.toDouble(),
                MIN_ROUGHNESS_SPAN,
            ),
            rangeNorm(
                novelty.toDouble(),
                ranges.noveltyP5.toDouble(),
                ranges.noveltyP95.toDouble(),
                MIN_NOVELTY_SPAN,
            ),
        )
        if (dtSec <= 0.0) return value
        val alpha = exp(-dtSec / if (target > value) riseTauSec else fallTauSec)
        value = (alpha * value + (1.0 - alpha) * target).toFloat()
        return value
    }

    fun reset() {
        value = 0f
    }

    companion object {

        const val W_LOUDNESS = 0.38
        const val W_CENTROID = 0.27
        const val W_ROUGHNESS = 0.10
        const val W_NOVELTY = 0.25

        /** Anzeigefenster der Lautheit unterhalb ihres P95. */
        private const val LOUD_WINDOW_DB = 24.0

        /** Unter dieser Spanne in Oktaven ist der Schwerpunkt aussagelos. */
        private const val MIN_CENTROID_SPAN = 0.3

        private const val MIN_ROUGHNESS_SPAN = 0.005
        private const val MIN_NOVELTY_SPAN = 0.02

        /** Die gewichtete Summe der vier bereits normierten Zutaten. */
        fun raw(loud: Double, centroid: Double, roughness: Double, novelty: Double): Double {
            val sum = W_LOUDNESS * loud + W_CENTROID * centroid +
                W_ROUGHNESS * roughness + W_NOVELTY * novelty
            return sum.coerceIn(0.0, 1.0)
        }

        /** 24-dB-Fenster unter dem lautesten Fünftel des Songs. */
        fun loud(loudDb: Double, loudP95Db: Double): Double {
            if (!loudDb.isFinite()) return 0.0
            val floor = loudP95Db - LOUD_WINDOW_DB
            return ((loudDb - floor) / LOUD_WINDOW_DB).coerceIn(0.0, 1.0)
        }

        /** Schwerpunkt in Oktaven; bei zu schmaler Spanne bleibt er neutral. */
        fun centroidNorm(centroidHz: Double, p5Log2: Double, p95Log2: Double): Double {
            if (centroidHz <= 0.0) return 0.0
            val span = p95Log2 - p5Log2
            if (span < MIN_CENTROID_SPAN) return 0.5
            return ((log2(centroidHz) - p5Log2) / span).coerceIn(0.0, 1.0)
        }

        /**
         * Lineare Normierung mit Totzonen-Guard: eine Zutat, die sich im Song
         * kaum bewegt, würde sonst ihr Rauschen auf 0..1 verstärken.
         */
        fun rangeNorm(value: Double, p5: Double, p95: Double, minSpan: Double): Double {
            val span = p95 - p5
            if (span < minSpan) return 0.0
            return ((value - p5) / span).coerceIn(0.0, 1.0)
        }

        /**
         * Zieht die Spannungskurve der Analyseframes aufs Beat-Raster und
         * verteilt sie auf die Anzeigeframes.
         *
         * Das Raster ist der halbe Beat: zwischen zwei Beats liegt genau ein
         * Stützpunkt, und dazwischen wird linear interpoliert. Ohne die
         * Interpolation wäre jeder Wechsel ein Sprung alle 250 ms, also ein
         * 4-Hz-Flackern der Gesamthelligkeit; ohne das Raster liefen
         * Helligkeit und Schlag auseinander.
         *
         * Ohne zwei brauchbare Beats (kein Tempo erkannt) wird nicht
         * quantisiert, sondern nur auf die Anzeigerate umgesetzt -- so
         * verlangt es der Bericht, und eine Division durch null gibt es
         * damit auch nicht.
         *
         * [curve] ist die geglättete Spannung je Analyseframe, [beats] die
         * aufsteigenden Beatzeiten in Sekunden. [out] nimmt [frames] Werte auf.
         */
        fun quantize(
            curve: FloatArray,
            curveFps: Double,
            beats: DoubleArray,
            drawFps: Int,
            frames: Int,
            out: FloatArray,
        ) {
            require(drawFps > 0) { "drawFps muss positiv sein: $drawFps" }
            require(out.size >= frames) { "out ist zu kurz: ${out.size} < $frames" }
            if (frames <= 0) return
            if (curve.isEmpty() || curveFps <= 0.0) {
                Arrays.fill(out, 0, frames, 0f)
                return
            }

            val grid = buildGrid(beats)
            if (grid.size < 2) {
                for (m in 0 until frames) {
                    out[m] = sampleAt(curve, curveFps, m.toDouble() / drawFps)
                }
                return
            }

            val gridValues = FloatArray(grid.size) { sampleAt(curve, curveFps, grid[it]) }
            var k = 0
            val last = grid.size - 1
            for (m in 0 until frames) {
                val t = m.toDouble() / drawFps
                when {
                    t <= grid[0] -> out[m] = gridValues[0]
                    t >= grid[last] -> out[m] = gridValues[last]
                    else -> {
                        while (k < last - 1 && grid[k + 1] <= t) k++
                        val span = grid[k + 1] - grid[k]
                        val w = if (span > 0.0) ((t - grid[k]) / span).toFloat() else 0f
                        out[m] = gridValues[k] + (gridValues[k + 1] - gridValues[k]) * w
                    }
                }
            }
        }

        /**
         * Beatzeiten plus die Mittelpunkte dazwischen, aufsteigend. Ein
         * unbrauchbares Intervall (gleicher oder rückwärts laufender Beat)
         * fällt weg, statt das Raster zu verzerren.
         */
        private fun buildGrid(beats: DoubleArray): DoubleArray {
            if (beats.size < 2) return DoubleArray(0)
            val grid = DoubleArray((beats.size - 1) * 2 + 1)
            var n = 0
            for (b in 0 until beats.size - 1) {
                val from = beats[b]
                val to = beats[b + 1]
                if (!(to > from)) continue
                grid[n++] = from
                grid[n++] = from + (to - from) / 2.0
            }
            if (n == 0) return DoubleArray(0)
            // Der letzte Beat schließt das Raster ab, damit auch das Ende
            // des letzten Intervalls einen Stützpunkt hat.
            grid[n++] = beats.last()
            return grid.copyOf(n)
        }

        /** Wert der Kurve zur Zeit [t], linear zwischen zwei Frames. */
        private fun sampleAt(curve: FloatArray, fps: Double, t: Double): Float {
            if (curve.size == 1) return curve[0]
            val pos = t * fps
            if (pos <= 0.0) return curve[0]
            val index = pos.toInt()
            if (index >= curve.size - 1) return curve[curve.size - 1]
            val w = (pos - index).toFloat()
            return curve[index] + (curve[index + 1] - curve[index]) * w
        }
    }
}
