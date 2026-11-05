package com.steimerbyte.glyphorchestra

import com.steimerbyte.glyphorchestra.glyph.Level
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prozent zu Rohwert.
 *
 * Die eine Zahl, die im SDK belegt ist, ist `GlyphFrame.DEFAULT_LIGHT` = 4000
 * fuer volle Helligkeit; zwischen 0 und 4000 gibt es keinen Sprung. Der Test
 * haelt beides fest: die Anker (0, 1, 50, 100) und dass die Abbildung monoton
 * und vollstaendig bleibt -- sonst waere ein Prozentwert am Regler eine
 * Zusage, die an einer Stelle verschwindet.
 */
class LevelTest {

    @Test
    fun `null ist dunkel`() {
        assertEquals(0, Level.value(0))
    }

    @Test
    fun `hundert ist der Vorgabewert des SDK`() {
        assertEquals(4000, Level.MAX)
        assertEquals(4000, Level.value(100))
    }

    @Test
    fun `der Rohwert bleibt im belegten Bereich`() {
        for (percent in 0..100) {
            val value = Level.value(percent)
            assertTrue("ausserhalb des Bereichs bei $percent: $value", value in 0..Level.MAX)
        }
    }

    @Test
    fun `jeder Prozentpunkt ist ein eigener Rohwert`() {
        val values = (0..100).map { Level.value(it) }
        assertEquals("doppelte Rohwerte: $values", 101, values.toSet().size)
    }

    @Test
    fun `die Abbildung ist linear und steigt`() {
        assertEquals(2000, Level.value(50))
        assertEquals(40, Level.value(1))
        assertEquals(1200, Level.value(30))
        assertEquals(2480, Level.value(62))
        for (percent in 1..100) {
            assertTrue(
                "nicht steigend bei $percent",
                Level.value(percent) > Level.value(percent - 1),
            )
        }
    }

    @Test
    fun `Werte ausserhalb werden geklemmt`() {
        assertEquals(Level.MAX, Level.value(140))
        assertEquals(0, Level.value(-5))
        assertEquals(0, Level.clamp(-5))
        assertEquals(100, Level.clamp(140))
    }

    @Test
    fun `die Kurzwahlen sind dreissig, fuenfundsechzig und hundert`() {
        // Die genauen Werte, nicht nur "drei aufsteigende Zahlen": 30/64/100
        // waere sonst gruen, und der 65-%-Knopf setzte still 64 %.
        assertArrayEquals(intArrayOf(30, 65, 100), Level.PERCENTS)
        for (percent in Level.PERCENTS) {
            assertTrue("$percent ausserhalb", percent in 0..Level.MAX_PERCENT)
        }
    }
}
