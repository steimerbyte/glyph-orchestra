package com.steimerbyte.glyphorchestra

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.ui.GlyphLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * "Tipp auf die Rueckseite" muss denselben Kanal treffen, den die Karte zeigt.
 * Ein Fehler hier faellt auf dem Geraet nur als "die Nachbar-LED leuchtet"
 * auf -- und das ist von einem echten Wunsch nicht zu unterscheiden.
 *
 * Geprueft wird jedes Geraet der Tabelle, nicht nur das Phone (3a): die
 * Geometrie ist fuer alle dieselbe Rechnung, nur die Streifen sind andere.
 */
class GlyphLayoutTest {

    private val devices: List<DeviceSpec> = DeviceCatalog.segment

    @Test
    fun `jeder Kanal hat genau einen Platz`() {
        for (spec in devices) {
            val layout = GlyphLayout(spec)
            assertEquals(spec.model, spec.channels, layout.slots.size)
            assertEquals(
                "${spec.model}: Index doppelt",
                (0 until spec.channels).toSet(),
                layout.slots.map { it.channel.index }.toSet(),
            )
        }
    }

    @Test
    fun `die Mitte eines Segments trifft seinen eigenen Kanal`() {
        for (spec in devices) {
            val layout = GlyphLayout(spec)
            for (slot in layout.slots) {
                assertEquals(
                    "${spec.model}: ${slot.channel.code}",
                    slot.channel,
                    layout.hit(slot.x, slot.y, tolerance = 0.001f),
                )
            }
        }
    }

    @Test
    fun `zwischen zwei Segmenten gewinnt das naehere`() {
        for (spec in devices) {
            val layout = GlyphLayout(spec)
            // Nur Nachbarn, die auf der Karte wirklich nebeneinander liegen:
            // auf dem Phone (2) folgt im Bogen C1 das Ende von C1 auf C2, und
            // dazwischen liegt die halbe Rueckseite.
            for (group in spec.groups) {
                val slots = layout.slots.filter { it.channel.group == group }
                for (index in 0 until slots.size - 1) {
                    val first = slots[index]
                    val second = slots[index + 1]
                    if (hypot(second.x - first.x, second.y - first.y) > GlyphLayout.HIT_TOLERANCE) {
                        continue
                    }
                    val nearSecond = layout.hit(
                        first.x + (second.x - first.x) * 0.8f,
                        first.y + (second.y - first.y) * 0.8f,
                    )
                    assertEquals(
                        "${spec.model}: ${first.channel.code}->${second.channel.code}",
                        second.channel,
                        nearSecond,
                    )
                }
            }
        }
    }

    @Test
    fun `ausserhalb der Karte trifft nichts`() {
        for (spec in devices) {
            val layout = GlyphLayout(spec)
            val slot = layout.slots.first()
            // Ein Punkt, den kein Kanal in Reichweite hat: gegenueber.
            assertNull(
                "${spec.model}: Treffer ausserhalb",
                layout.hit(if (slot.x < 0.5f) 0.98f else 0.02f, if (slot.y < 0.5f) 0.98f else 0.02f),
            )
        }
    }

    @Test
    fun `der Treffer bleibt in der Toleranz`() {
        val layout = GlyphLayout(DeviceCatalog.PHONE_3A)
        val slot = layout.slots.first()
        // Radial nach aussen: in dieser Richtung liegt kein Nachbar, also
        // misst der Punkt wirklich die Toleranz.
        val dx = slot.x - 0.5f
        val dy = slot.y - 0.5f
        val length = hypot(dx, dy)
        val reach = GlyphLayout.HIT_TOLERANCE
        assertEquals(
            slot.channel,
            layout.hit(slot.x + dx / length * reach * 0.9f, slot.y + dy / length * reach * 0.9f),
        )
        assertNotEquals(
            slot.channel,
            layout.hit(slot.x + dx / length * reach * 1.1f, slot.y + dy / length * reach * 1.1f),
        )
    }

    @Test
    fun `die Gruppenzeichen stehen auf der Karte`() {
        for (spec in devices) {
            val layout = GlyphLayout(spec)
            for (group in spec.groups) {
                val (x, y) = layout.labelPoint(group)
                assertTrue("${spec.model} $group: x=$x", x in 0f..1f)
                assertTrue("${spec.model} $group: y=$y", y in 0f..1f)
            }
        }
    }

    @Test
    fun `der Ring des Phone 3a bleibt ein Ring`() {
        // Alle Segmente liegen auf einem Kreis um die Mitte -- das war die
        // am Geraet gepruefte Geometrie und darf nicht verrutschen.
        val layout = GlyphLayout(DeviceCatalog.PHONE_3A)
        for (slot in layout.slots) {
            val slotArc = slot as GlyphLayout.Slot.ArcSlot
            val distance = hypot(slot.x - 0.5f, slot.y - 0.5f)
            assertEquals("${slot.channel.code}", 0.5f, distance, 0.001f)
            assertEquals(0.5f, slotArc.cx, 0.001f)
            assertEquals(0.5f, slotArc.cy, 0.001f)
        }
    }

    @Test
    fun `die Leiste des Phone 4a steht senkrecht`() {
        val layout = GlyphLayout(DeviceCatalog.PHONE_4A)
        for (slot in layout.slots) {
            val bar = slot as GlyphLayout.Slot.BarSlot
            assertEquals("x", 0.68f, slot.x, 0.001f)
            // 90 Grad: die Leiste laeuft von oben nach unten.
            assertEquals("Drehung", 90f, bar.rotation, 0.01f)
        }
        assertEquals("A1", layout.slots.first().channel.code)
        assertTrue("A1 liegt nicht oben", layout.slots.first().y < 0.25f)
    }

    @Test
    fun `die Mitte findet den Kanal ueber seinen Code`() {
        val layout = GlyphLayout(DeviceCatalog.PHONE_2A)
        val center = layout.centerOf("C1")!!
        assertEquals(layout.slots.first().x, center.first, 0.0001f)
        assertNull(layout.centerOf("Z9"))
    }

    @Test
    fun `eine leere Tabelle hat keine Plaetze`() {
        val layout = GlyphLayout(DeviceSpec.unsupported("Pixel 9"))
        assertTrue(layout.slots.isEmpty())
        assertNull(layout.hit(0.5f, 0.5f))
    }
}
