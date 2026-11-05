package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.Channel
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.StripShape
import com.steimerbyte.glyphorchestra.glyph.Level
import com.steimerbyte.glyphorchestra.ui.GlyphLayout
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Bildet die Bandpegel eines Frames auf die 36 Glyph-Kanäle ab.
 *
 * Drei Dinge stecken hier drin, und alle drei sind notwendig, weil rohe
 * Bandpegel als Helligkeit unbrauchbar sind:
 *
 * 1. **Ringordnung.** Die Frequenzachse läuft einmal um das Gerät. Die
 *    Reihenfolge kommt aus [GlyphLayout] (Mittelwinkel der Bogensegmente) und
 *    beginnt an der Naht -- dem Streifen, der 180 Grad enthält. Sortiert man
 *    stattdessen ab 0 Grad, zerfällt der Bass-Streifen B in zwei Enden des
 *    Arrays und dieselben tiefen Frequenzen erscheinen zweimal.
 * 2. **dB zu Helligkeit.** Ein 30-dB-Fenster unter dem bandeigenen P95, dann
 *    Gamma 1,8 mit sanfter Kompression. Linear in dB wirkt der halbe Ring
 *    dauerhaft an, Gamma 2,2 macht die unteren 60 % unsichtbar.
 * 3. **Zeitverhalten.** Mindesthaltezeit, Schmitt-Trigger, Release-Rampe und
 *    Totband gegen Flackern -- ohne sie zappelt jede LED an ihrer Schwelle.
 *
 * Geschrieben wird immer `levels[channel.index]`, nie nach Ringposition: das
 * SDK adressiert über den Index, und `DeviceSpec.all` ist nach Gruppen
 * (A, B, C) sortiert, nicht nach Ring.
 *
 * [p95Db]/[p10Db] sind die Perzentile je Band über den ganzen Song
 * (Index 0..15 = Log-Bänder, 16 = Tieftonband). Ohne sie ist keine
 * Pegelanzeige kalibrierbar; die Vorgaben sind nur ein Notbehelf für einen
 * einzelnen Frame.
 */
class BandToChannel(
    spec: DeviceSpec,
    p95Db: FloatArray = FloatArray(BAND_COUNT) { DEFAULT_P95_DB },
    p10Db: FloatArray = FloatArray(BAND_COUNT) { DEFAULT_P10_DB },
) {

    private val reference95: FloatArray
    private val reference10: FloatArray

    /** Kanäle in Ringreihenfolge; Position p ist der Ort auf dem Ring. */
    private val channels: List<Channel>

    /** SDK-Indizes in Ringreihenfolge. */
    val ring: IntArray

    /** Mittenfrequenz je Ringposition in Hz. */
    val midHz: DoubleArray

    private val band0: IntArray
    private val bandWeight: FloatArray
    private val wantsLowBand: BooleanArray

    private val latched: BooleanArray
    private val holdUntil: IntArray
    private val heldPeak: FloatArray
    private val previous: FloatArray
    private var frame = 0

    /** Zwischenpuffer der Bandhelligkeiten, damit [map] nicht alloziert. */
    private val bandY = FloatArray(BAND_COUNT)

    init {
        require(p95Db.size >= BAND_COUNT) { "p95Db braucht $BAND_COUNT Werte" }
        require(p10Db.size >= BAND_COUNT) { "p10Db braucht $BAND_COUNT Werte" }
        reference95 = p95Db.copyOf(BAND_COUNT)
        reference10 = p10Db.copyOf(BAND_COUNT)

        val arcs = GlyphLayout(spec).slots.filterIsInstance<GlyphLayout.Slot.ArcSlot>()
        channels = if (arcs.isEmpty()) {
            // Leisten und Punkte (Phone 4a/4b) haben keinen Ring; dort ist die
            // Reihenfolge des SDK bereits die Ordnung von unten nach oben.
            spec.all.sortedBy { it.index }
        } else {
            val seam = seamOf(spec)
            arcs.sortedBy { ((it.start + it.sweep / 2f - seam) + 360f) % 360f }
                .map { it.channel }
        }

        val count = channels.size
        ring = IntArray(count) { channels[it].index }
        midHz = DoubleArray(count)
        band0 = IntArray(count)
        bandWeight = FloatArray(count)
        wantsLowBand = BooleanArray(count)
        latched = BooleanArray(count)
        holdUntil = IntArray(count)
        heldPeak = FloatArray(count)
        previous = FloatArray(count)

        if (count > 0) {
            // Kanalachse: 100 Hz bis 11 025 Hz logarithmisch über den Ring.
            val ratio = F_MAX_HZ / F_MIN_HZ
            fun edge(p: Int): Double = F_MIN_HZ * ratio.pow(p.toDouble() / count)

            // Bandachse: dieselbe Spanne in 16 gleichen Schritten. Der
            // Bezug kommt aus der Bandtabelle, nicht aus den Kanälen --
            // sonst hängt die Zuordnung an der Kanalzahl.
            val bandEdges = DoubleArray(BANDS + 1) { F_MIN_HZ * ratio.pow(it.toDouble() / BANDS) }
            val fc0 = sqrt(bandEdges[0] * bandEdges[1])
            val fcLast = sqrt(bandEdges[BANDS - 1] * bandEdges[BANDS])
            val step = (ln(fcLast) - ln(fc0)) / (BANDS - 1)

            for (p in 0 until count) {
                val low = edge(p)
                val mid = sqrt(low * edge(p + 1))
                midHz[p] = mid
                val v = ((ln(mid) - ln(fc0)) / step)
                    .coerceIn(0.0, (BANDS - 1).toDouble())
                val base = min(BANDS - 2, v.toInt())
                band0[p] = base
                bandWeight[p] = (v - base).toFloat()
                // Das Tieftonband 35..110 Hz liegt unter dem 16-Band-Raster;
                // nur der tiefste Kanal (B1, 100..114 Hz) fällt darunter.
                // Ohne ihn wäre die Kick bei 50..80 Hz gar nicht zu sehen.
                wantsLowBand[p] = low < Dsp.LOW_BAND_HZ_TO
            }
        }
    }

    /**
     * Ein Anzeigeframe: [bandsDb] (dBFS, 17 Werte) und die Spannung werden zu
     * [levels] (36 Werte, nach `Channel.index`).
     *
     * [levels] wird vollständig geschrieben; der Aufrufer darf denselben
     * Puffer wiederverwenden. Zustand (Haltezeit, Hysterese, Release) bleibt
     * im Objekt, deshalb gehört zu einem Song genau eine Instanz.
     */
    fun map(bandsDb: FloatArray, tension: Float, levels: IntArray) {
        require(levels.size >= CHANNELS) { "levels braucht $CHANNELS Werte" }
        require(bandsDb.size >= BAND_COUNT) { "bandsDb braucht $BAND_COUNT Werte" }
        Arrays.fill(levels, 0)

        var silent = true
        for (b in 0 until BAND_COUNT) {
            if (bandsDb[b] >= SILENCE_DB) {
                silent = false
                break
            }
        }
        if (silent) {
            // Der Bericht blendet über 150 ms aus. Unter -70 dBFS ist aber
            // in keinem Band mehr etwas da; ein harter Schnitt ist unsichtbar
            // und lässt keinen Zustand stehen, der nach einer Pause falsch
            // weiterliefe.
            reset()
            return
        }

        for (b in 0 until BAND_COUNT) bandY[b] = brightness(bandsDb[b], b)

        val t = tension.coerceIn(0f, 1f)
        for (p in channels.indices) {
            val base = band0[p]
            val w = bandWeight[p]
            var y = (1f - w) * bandY[base] + w * bandY[base + 1]
            if (wantsLowBand[p]) y = max(y, bandY[LOW_BAND_INDEX])

            val target = rawLevel(y, t)
            levels[channels[p].index] = step(p, target)
        }
        frame++
    }

    /** Setzt Haltezeiten und Hysterese zurück (neuer Song, Sprung im Zeitlauf). */
    fun reset() {
        Arrays.fill(latched, false)
        Arrays.fill(holdUntil, 0)
        Arrays.fill(heldPeak, 0f)
        Arrays.fill(previous, 0f)
        frame = 0
    }

    /** Helligkeit eines Bandes, 0..1: 30-dB-Fenster unter dem P95. */
    private fun brightness(db: Float, band: Int): Float {
        if (db < SILENCE_DB) return 0f
        // Ein totes Band (kein Höhenanteil im Song) würde sein Rauschen
        // sonst auf den ganzen Bereich verstärken.
        if (reference95[band] - reference10[band] < DEAD_SPAN_DB) return 0f
        val floor = reference95[band] - WINDOW_DB
        return ((db - floor) / WINDOW_DB).coerceIn(0f, 1f)
    }

    /**
     * Normierte Helligkeit zu Rohwerten: Gamma 1,8, Kompression oberhalb 0,85
     * und der Spannungssockel für bereits leuchtende Kanäle.
     */
    private fun rawLevel(y: Float, tension: Float): Float {
        val compressed = if (y > COMPRESS_FROM) {
            COMPRESS_FROM + COMPRESS_REST * tanh((y - COMPRESS_FROM) / COMPRESS_REST)
        } else {
            y
        }
        var raw = RAW_MAX * compressed.pow(GAMMA)
        if (y >= PEDESTAL_MIN) raw = min(RAW_MAX, raw + tension * PEDESTAL_RAW)
        return raw
    }

    /**
     * Zeitverhalten eines Kanals: Schmitt-Trigger, Mindesthaltezeit,
     * Release-Rampe, Totband -- in dieser Reihenfolge aus dem Bericht.
     */
    private fun step(p: Int, target: Float): Int {
        if (target >= ON_RAW) {
            latched[p] = true
        } else if (target <= OFF_RAW) {
            latched[p] = false
        }

        var value = if (latched[p]) target else 0f
        if (value > HOLD_MIN_RAW) {
            if (holdUntil[p] <= frame) {
                heldPeak[p] = value
                holdUntil[p] = frame + HOLD_FRAMES
            }
            if (holdUntil[p] > frame) value = max(value, heldPeak[p])
        }
        // Langsamer Abfall, sofortiger Anstieg: ein Vollausschlag fällt in
        // gut 600 ms auf 8 %, ein Kick verliert seine Kante nicht.
        value = max(value, previous[p] - RELEASE_RAW)
        // Totband gegen 1-LSB-Glimmern: kleine Änderungen bleiben stehen.
        if (abs(value - previous[p]) < DEADBAND_RAW) value = previous[p]
        previous[p] = value
        return value.roundToInt().coerceIn(0, Level.MAX)
    }

    companion object {
        /** Ausgabebreite des SDK-Arrays. */
        const val CHANNELS = 36

        /** Die 16 Log-Bänder aus [Dsp.BANDS]. */
        const val BANDS = Dsp.BANDS

        /** Index des zusätzlichen Tieftonbands in `bandsDb`. */
        const val LOW_BAND_INDEX = BANDS

        /** `bandsDb` hat 17 Werte: 16 Log-Bänder plus Tieftonband. */
        const val BAND_COUNT = BANDS + 1

        const val F_MIN_HZ = 100.0
        const val F_MAX_HZ = 11025.0

        /** Anzeigefenster unter dem bandeigenen P95. */
        private const val WINDOW_DB = 30f

        /** Ab hier ist ein Band leer; darunter schaltet auch die Stille. */
        private const val SILENCE_DB = -70f

        /** Ein Band, das sich im Song um weniger als das bewegt, ist tot. */
        private const val DEAD_SPAN_DB = 6f

        private const val GAMMA = 1.8f
        private const val COMPRESS_FROM = 0.85f
        private const val COMPRESS_REST = 0.15f

        /** Intern wird auf 4095 gerechnet; die SDK-Grenze ist 4000. */
        private const val RAW_MAX = 4095f

        private const val PEDESTAL_MIN = 0.02f
        private const val PEDESTAL_RAW = 492f

        /** Schmitt-Trigger: an bei 0,08, aus bei 0,04 (328/164 Rohwerte). */
        private const val ON_RAW = 328f
        private const val OFF_RAW = 164f

        /** Mindesthaltezeit 4 Frames = 133 ms bei 30 fps. */
        private const val HOLD_FRAMES = 4
        private const val HOLD_MIN_RAW = 82f

        /** Release 45 dB/s; im 30-dB-Fenster sind das 0,05 je Frame. */
        private const val RELEASE_RAW = 205f

        /** Totband 48 Rohwerte = 1,2 %. */
        private const val DEADBAND_RAW = 48f

        private const val DEFAULT_P95_DB = -6f
        private const val DEFAULT_P10_DB = -60f

        /** Der Start des Streifens, der 180 Grad enthält -- die Naht. */
        private fun seamOf(spec: DeviceSpec): Float {
            var fallback: Float? = null
            for (strip in spec.strips) {
                val arc = strip.shape as? StripShape.Arc ?: continue
                if (fallback == null) fallback = arc.start
                if (covers(arc.start, arc.sweep, 180f)) return arc.start
            }
            return fallback ?: 0f
        }

        /** Liegt [degrees] auf dem Bogen? Ein negativer Sweep läuft rückwärts. */
        private fun covers(start: Float, sweep: Float, degrees: Float): Boolean {
            val offset = ((degrees - start) % 360f + 360f) % 360f
            return if (sweep >= 0f) {
                offset <= sweep
            } else {
                ((start - degrees) % 360f + 360f) % 360f <= -sweep
            }
        }
    }
}
