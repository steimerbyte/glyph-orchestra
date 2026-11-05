package com.steimerbyte.glyphorchestra.ui

import com.steimerbyte.glyphorchestra.device.Channel
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.Strip
import com.steimerbyte.glyphorchestra.device.StripShape
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wo die Kanaele eines Geraets liegen -- reine Rechnung im Einheitsquadrat.
 *
 * Warum das nicht in der View steht: Treffer und Zeichnung muessen dieselbe
 * Geometrie benutzen, sonst leuchtet beim Tippen die Nachbar-LED. Hier ist
 * beides eine Formel, und [GlyphLayoutTest] prueft es ohne Bildschirm.
 *
 * Zwei Formen decken alle Geraete ab: [StripShape.Arc] fuer die Ringe des
 * Phone (1), (2), (2a) und (3a), [StripShape.Line] fuer die senkrechten
 * Leisten des Phone (4a)/(4b) und fuer einzelne Punkte.
 */
class GlyphLayout(val spec: DeviceSpec) {

    /** Ein Kanal an seinem Platz. */
    sealed interface Slot {
        val channel: Channel

        /** Mittelpunkt im Einheitsquadrat (0..1, y nach unten). */
        val x: Float
        val y: Float

        /** Ein Bogenstueck; [start]/[sweep] in Grad Uhrzeigersinn ab 12 Uhr. */
        data class ArcSlot(
            override val channel: Channel,
            override val x: Float,
            override val y: Float,
            val cx: Float,
            val cy: Float,
            val radius: Float,
            val start: Float,
            val sweep: Float,
        ) : Slot

        /** Ein gerades Stueck; [rotation] in Grad gegen die Waagerechte. */
        data class BarSlot(
            override val channel: Channel,
            override val x: Float,
            override val y: Float,
            val length: Float,
            val width: Float,
            val rotation: Float,
        ) : Slot
    }

    val slots: List<Slot> = spec.strips.flatMap(::slotsOf)

    /**
     * Der Kanal unter ([x], [y]); `null` heisst "nichts getroffen".
     *
     * Der naechste Mittelpunkt gewinnt. Das ist bei gleichmaessig verteilten
     * Segmenten dasselbe Ergebnis wie eine Winkelrechnung, funktioniert aber
     * auch fuer die Leisten und Punkte -- und die Luecken zwischen zwei
     * Segmenten bleiben Luecken, weil [tolerance] kleiner ist als der halbe
     * Abstand zweier Nachbarn.
     */
    fun hit(x: Float, y: Float, tolerance: Float = HIT_TOLERANCE): Channel? {
        var best: Slot? = null
        var bestDistance = tolerance
        for (slot in slots) {
            val distance = hypot(slot.x - x, slot.y - y)
            if (distance < bestDistance) {
                best = slot
                bestDistance = distance
            }
        }
        return best?.channel
    }

    /** Der Mittelpunkt eines Kanals, sonst `null`. */
    fun centerOf(code: String): Pair<Float, Float>? {
        val channel = spec.of(code) ?: return null
        val slot = slots.firstOrNull { it.channel == channel } ?: return null
        return slot.x to slot.y
    }

    /**
     * Der Punkt fuer das Gruppenzeichen: Schwerpunkt der Gruppe, vom Rand
     * weggeschoben, damit der Buchstabe neben dem Streifen steht und nicht
     * darauf.
     */
    fun labelPoint(group: String): Pair<Float, Float> {
        val members = slots.filter { it.channel.group == group }
        if (members.isEmpty()) return 0.5f to 0.5f
        val cx = members.sumOf { it.x.toDouble() }.toFloat() / members.size
        val cy = members.sumOf { it.y.toDouble() }.toFloat() / members.size
        val dx = cx - 0.5f
        val dy = cy - 0.5f
        val length = hypot(dx, dy)
        if (length < 0.01f) return cx to cy
        val push = LABEL_PUSH / length
        return (cx + dx * push).coerceIn(0.04f, 0.96f) to (cy + dy * push).coerceIn(0.04f, 0.96f)
    }

    private fun slotsOf(strip: Strip): List<Slot> = when (val shape = strip.shape) {
        is StripShape.Arc -> {
            val per = shape.sweep / strip.count
            (0 until strip.count).map { index ->
                val start = shape.start + per * index
                val mid = start + per / 2f
                Slot.ArcSlot(
                    channel = Channel(strip.firstIndex + index, strip.group, strip.codes[index]),
                    x = shape.cx + shape.radius * HALF * sin(Math.toRadians(mid.toDouble())).toFloat(),
                    y = shape.cy - shape.radius * HALF * cos(Math.toRadians(mid.toDouble())).toFloat(),
                    cx = shape.cx,
                    cy = shape.cy,
                    radius = shape.radius * HALF,
                    start = start,
                    sweep = per,
                )
            }
        }

        is StripShape.Line -> {
            val dx = shape.x1 - shape.x0
            val dy = shape.y1 - shape.y0
            val length = sqrt(dx * dx + dy * dy)
            // Ein Punkt hat keine Richtung: als Quadrat zeichnen, nicht als
            // Strich mit erfundener Achse.
            val dot = length < POINT_EPSILON
            val rotation = if (dot) {
                0f
            } else {
                Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            }
            (0 until strip.count).map { index ->
                val t = (index + 0.5f) / strip.count
                Slot.BarSlot(
                    channel = Channel(strip.firstIndex + index, strip.group, strip.codes[index]),
                    x = shape.x0 + dx * t,
                    y = shape.y0 + dy * t,
                    length = if (dot) DOT else length / strip.count * BAR_GAP,
                    width = if (dot) DOT else THICKNESS,
                    rotation = rotation,
                )
            }
        }
    }

    companion object {
        /** Halbe Kante: der Bogen mit Radius 1 liegt genau im Einheitsquadrat. */
        private const val HALF = 0.5f

        /** Strichstaerke als Anteil der Kante -- passt zur Ringstaerke der Karte. */
        const val THICKNESS = 0.078f

        /** Laenge eines einzelnen Punktes (Phone (1): A1, B1, E1). */
        const val DOT = 0.085f

        /** Anteil der Segmentlaenge, der wirklich gezeichnet wird: der Rest ist Luecke. */
        private const val BAR_GAP = 0.78f

        /** Ab dieser Laenge ist eine "Gerade" ein Punkt. */
        private const val POINT_EPSILON = 0.001f

        /**
         * Treffertoleranz im Einheitsquadrat. Der halbe Abstand zweier
         * Nachbarn liegt bei 0.037 (Ring) bis 0.057 (Leiste); 0.045 trifft
         * also jeden Kanal, ohne in die Mitte zwischen zwei zu greifen.
         */
        const val HIT_TOLERANCE = 0.045f

        /** Wie weit das Gruppenzeichen vom Schwerpunkt nach aussen rutscht. */
        private const val LABEL_PUSH = 0.10f
    }
}
