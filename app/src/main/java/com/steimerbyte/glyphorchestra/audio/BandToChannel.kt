package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.Channel
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.StripShape
import com.steimerbyte.glyphorchestra.glyph.Level
import com.steimerbyte.glyphorchestra.ui.GlyphLayout
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ceil
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
 *
 * [fps] ist die Rate, mit der [map] aufgerufen wird -- die Analyse-, nicht die
 * Anzeigerate. Haltezeit, Release, Stille-Entprellung und Ausblendung sind in
 * Sekunden festgelegt und werden daraus in Frames umgerechnet; feste
 * Framezahlen wären bei 86,1 fps um den Faktor 2,87 zu kurz.
 */
class BandToChannel(
    spec: DeviceSpec,
    private val fps: Double,
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

    /** Wie viele Frames der Stille-Entprellung schon still waren. */
    private var silentRun = 0

    /** Restframes der Ausblendung nach erkannter Stille; 0 = keine. */
    private var fadeLeft = 0
    private val fadeFrom: FloatArray

    /** Zwischenpuffer der Bandhelligkeiten, damit [map] nicht alloziert. */
    private val bandY = FloatArray(BAND_COUNT)

    /** Mindesthaltezeit in Frames; aufgerundet, damit sie nie unter 133 ms fällt. */
    private val holdFrames: Int = ceil(HOLD_SEC * fps).toInt()

    /** Release je Frame: 45 dB/s im 30-dB-Fenster sind 1,5 normierte Einheiten/s. */
    private val releaseRaw: Float = (RELEASE_PER_SEC * RAW_MAX / fps).toFloat()

    /**
     * Totband als Anteil des Vollausschlags, nicht als Framezahl: es ist eine
     * Pegelschwelle gegen 1-LSB-Glimmern, keine Zeitkonstante. Aus der
     * Analyserate abgeleitet würde es auf 17 Rohwerte fallen und das
     * Glimmern zurückholen.
     */
    private val deadbandRaw: Float = DEADBAND_FRACTION * RAW_MAX

    /** Erst nach 300 ms Stille wird ausgeblendet, nicht bei jeder Lücke. */
    private val silenceFrames: Int = ceil(SILENCE_SEC * fps).toInt()

    /** Ausblenddauer in Frames; mindestens einer, sonst gäbe es keinen Abfall. */
    private val fadeFrames: Int = max(1, ceil(FADE_SEC * fps).toInt())

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
        fadeFrom = FloatArray(count)

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

        if (isSilent(bandsDb)) {
            // Eine Pause ist erst nach 300 ms eine Pause: kurz vorher bleibt
            // der letzte Wert stehen, damit Phrasenlücken nicht dunkel werden.
            // Erst danach läuft die Ausblendung über 150 ms.
            if (++silentRun > silenceFrames) fade(levels) else hold(levels)
            frame++
            return
        }
        silentRun = 0
        fadeLeft = 0

        for (b in 0 until BAND_COUNT) bandY[b] = brightness(bandsDb[b], b)

        val t = tension.coerceIn(0f, 1f)
        for (p in channels.indices) {
            val base = band0[p]
            val w = bandWeight[p]
            var y = (1f - w) * bandY[base] + w * bandY[base + 1]
            if (wantsLowBand[p]) y = max(y, bandY[LOW_BAND_INDEX])

            levels[channels[p].index] = step(p, y, t)
        }
        frame++
    }

    /** Setzt Haltezeiten und Hysterese zurück (neuer Song, Sprung im Zeitlauf). */
    fun reset() {
        Arrays.fill(latched, false)
        Arrays.fill(holdUntil, 0)
        Arrays.fill(heldPeak, 0f)
        Arrays.fill(previous, 0f)
        silentRun = 0
        fadeLeft = 0
        frame = 0
    }

    /** Kein Band über der Stillegrenze. */
    private fun isSilent(bandsDb: FloatArray): Boolean {
        for (b in 0 until BAND_COUNT) if (bandsDb[b] >= SILENCE_DB) return false
        return true
    }

    /** Kurze Lücke: der zuletzt gezeigte Wert bleibt stehen. */
    private fun hold(levels: IntArray) {
        for (p in channels.indices) {
            levels[channels[p].index] = previous[p].roundToInt().coerceIn(0, Level.MAX)
        }
    }

    /**
     * Ausblenden nach erkannter Stille: linear über 150 ms vom Wert beim
     * Beginn der Ausblendung auf 0. Eine multiplikative Hüllkurve käme der
     * Null nur nahe, hier muss am Ende exakt 0 stehen -- sonst bliebe nach
     * einer Pause ein Rest leuchten.
     */
    private fun fade(levels: IntArray) {
        if (fadeLeft == 0) {
            System.arraycopy(previous, 0, fadeFrom, 0, previous.size)
            fadeLeft = fadeFrames
        }
        fadeLeft--
        for (p in channels.indices) {
            val value = if (fadeLeft > 0) fadeFrom[p] * fadeLeft / fadeFrames else 0f
            previous[p] = value
            levels[channels[p].index] = value.roundToInt()
        }
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
     * und der Spannungssockel. Der Sockel darf nur auf Kanäle, die schon
     * eingerastet sind -- auf einen dunklen Kanal addiert, überschritte er
     * allein die Einschaltschwelle und der Ausschaltpunkt wäre unerreichbar.
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
     *
     * Der Trigger läuft auf [y], der Helligkeit vor Gamma und Sockel: 0,08 und
     * 0,04 sind Anteile der Helligkeit. Am gamma-korrigierten Rohwert gemessen
     * läge die Einschaltschwelle bei y ≈ 0,24, also dreimal zu hoch, und die
     * Kanäle blieben bei leisen Passagen dunkel.
     */
    private fun step(p: Int, y: Float, tension: Float): Int {
        if (y >= ON_Y) {
            latched[p] = true
        } else if (y <= OFF_Y) {
            latched[p] = false
        }

        var value = if (latched[p]) rawLevel(y, tension) else 0f
        // Die Haltezeit wird am lauten Frame aufgezogen und pinnt den Wert
        // danach unabhaengig vom aktuellen y: ein 11-ms-Transient soll
        // 133 ms sichtbar bleiben und nicht im naechsten Frame schon fallen.
        if (latched[p] && y >= HOLD_MIN_Y && holdUntil[p] <= frame) {
            heldPeak[p] = value
            holdUntil[p] = frame + holdFrames
        }
        if (holdUntil[p] > frame) value = max(value, heldPeak[p])
        // Langsamer Abfall, sofortiger Anstieg: ein Vollausschlag fällt in
        // gut 600 ms auf 8 %, ein Kick verliert seine Kante nicht.
        value = max(value, previous[p] - releaseRaw)
        // Totband gegen 1-LSB-Glimmern: kleine Änderungen bleiben stehen.
        // Nur bei Werten über 0: das Totband darf das Ausschalten nicht
        // verhindern, sonst bliebe ein Rest von bis zu 48 Rohwerten stehen.
        if (value > 0f && abs(value - previous[p]) < deadbandRaw) value = previous[p]
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

        /**
         * Schmitt-Trigger auf y (Helligkeit vor Gamma): an bei 0,08, aus bei
         * 0,04 (Bericht §5.5). Die Rohwerte 328/164 im Bericht sind nur die
         * lineare Übersetzung dieser Anteile.
         */
        private const val ON_Y = 0.08f
        private const val OFF_Y = 0.04f

        /** Ab hier greift die Mindesthaltezeit (Bericht §5.5, Stufe 2). */
        private const val HOLD_MIN_Y = 0.02f

        /**
         * Mindesthaltezeit 133 ms. Aufgerundet, damit sie an keiner Rate
         * darunter liegt: bei 86,133 fps sind das 12 Frames = 139 ms,
         * abgerundet wären es 127 ms und damit zu kurz.
         */
        private const val HOLD_SEC = 0.133

        /**
         * Release 45 dB/s, im 30-dB-Fenster 1,5 normierte Einheiten je
         * Sekunde. Je Frame ist das 1,5·4095/fps, nicht 0,05·4095: der Wert
         * 205 gilt nur bei 30 fps.
         */
        private const val RELEASE_PER_SEC = 1.5

        /** Stille zählt erst ab 300 ms (Bericht §5.6), nicht ab dem ersten stillen Frame. */
        private const val SILENCE_SEC = 0.3

        /** Ausblenddauer nach erkannter Stille. */
        private const val FADE_SEC = 0.15

        /** Totband 1,2 % des Vollausschlags (Bericht §5.5, Stufe 4). */
        private const val DEADBAND_FRACTION = 0.012f

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
