package com.steimerbyte.glyphorchestra

import com.steimerbyte.glyphorchestra.device.Channel
import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.Detection
import com.steimerbyte.glyphorchestra.glyph.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Kanaltabellen der Geraete, festgenagelt.
 *
 * Diese Zahlen stammen aus den `Code_*`-Klassen der AAR (auf dem Phone (3a):
 * C1..C20 = 0..19, A1..A11 = 20..30, B1..B5 = 31..35). Sie stehen hier ein
 * zweites Mal, weil ein stiller Versatz sonst die falsche LED anspricht und
 * auf dem Geraet niemand nachrechnen kann, welcher Fehler es war.
 */
class DeviceCatalogTest {

    private val phone3a = DeviceCatalog.PHONE_3A

    // ------------------------------------------------------------- Phone (3a)

    @Test
    fun `das Phone 3a hat sechsunddreissig Kanaele`() {
        assertEquals(36, phone3a.channels)
        assertEquals(36, phone3a.all.size)
        // Reihenfolge ist nach Gruppe (A, B, C), nicht nach Index: die Tabelle
        // folgt der Rueckseite, nicht dem SDK-Array.
        assertEquals((0..35).toSet(), phone3a.all.map { it.index }.toSet())
    }

    @Test
    fun `jeder Kanal kennt seine Gruppe`() {
        assertEquals(0, phone3a.of("C1")!!.index)
        assertEquals(19, phone3a.of("C20")!!.index)
        assertEquals(20, phone3a.of("A1")!!.index)
        assertEquals(30, phone3a.of("A11")!!.index)
        assertEquals(31, phone3a.of("B1")!!.index)
        assertEquals(35, phone3a.of("B5")!!.index)
    }

    @Test
    fun `die Gruppen haben die Laengen der Bibliothek`() {
        assertEquals(listOf("A", "B", "C"), phone3a.groups)
        assertEquals(11, phone3a.group("A").size)
        assertEquals(5, phone3a.group("B").size)
        assertEquals(20, phone3a.group("C").size)
    }

    // ------------------------------------------------------------ Alle Geraete

    @Test
    fun `jedes Geraet hat genau so viele Kanaele wie seine Groesse angibt`() {
        for (spec in DeviceCatalog.segment) {
            assertEquals(spec.model, spec.channels, spec.all.size)
            assertEquals(
                "${spec.model}: Index doppelt",
                (0 until spec.channels).toSet(),
                spec.all.map { it.index }.toSet(),
            )
            assertEquals(
                "${spec.model}: Code doppelt",
                spec.channels,
                spec.all.map { it.code }.toSet().size,
            )
        }
    }

    @Test
    fun `die Codes folgen der Schreibweise des SDK`() {
        // Phone (2a): der Bogen heisst C_1..C_24, die Einzelkanaele "A" und "B".
        val twoA = DeviceCatalog.PHONE_2A
        assertEquals("C1", twoA.of("C1")!!.code)
        assertEquals("C24", twoA.of("C24")!!.code)
        assertNotNull(twoA.of("A"))
        assertNotNull(twoA.of("B"))
        assertNull(twoA.of("A1"))

        // Phone (2): A1/A2, C1_1..C1_16, C2..C6, D1_1..D1_8, E1.
        val two = DeviceCatalog.PHONE_2
        assertEquals(0, two.of("A1")!!.index)
        assertEquals(1, two.of("A2")!!.index)
        assertEquals(3, two.of("C1_1")!!.index)
        assertEquals(18, two.of("C1_16")!!.index)
        assertEquals(19, two.of("C2")!!.index)
        assertEquals(24, two.of("E1")!!.index)
        assertEquals(25, two.of("D1_1")!!.index)
        assertEquals(32, two.of("D1_8")!!.index)
        assertNull(two.of("C2_1"))

        // Phone (1): A1, B1, C1..C4, E1, D1_1..D1_8.
        val one = DeviceCatalog.PHONE_1
        assertEquals(2, one.of("C1")!!.index)
        assertEquals(5, one.of("C4")!!.index)
        assertEquals(6, one.of("E1")!!.index)
        assertEquals(14, one.of("D1_8")!!.index)

        // Phone (4a)/(4b): eine Leiste, A_1..A_6 bzw. A_1..A_4.
        assertEquals("A6", DeviceCatalog.PHONE_4A.of("A6")!!.code)
        assertEquals(5, DeviceCatalog.PHONE_4A.of("A6")!!.index)
        assertEquals(3, DeviceCatalog.PHONE_4B.of("A4")!!.index)
    }

    @Test
    fun `ein unbekannter Code findet keinen Kanal`() {
        assertNull(phone3a.of("Z1"))
        assertNull(phone3a.of("a1"))
        assertNull(phone3a.of(""))
    }

    @Test
    fun `aus Codes wird ein Rahmen`() {
        val frame = phone3a.frame(listOf("A1", "C20", "Z9"), Level.value(65))
        assertEquals(36, frame.size)
        assertEquals(Level.value(65), frame[phone3a.of("A1")!!.index])
        assertEquals(Level.value(65), frame[phone3a.of("C20")!!.index])
        assertEquals(2, frame.count { it > 0 })
    }

    @Test
    fun `die Codes eines Rahmens stehen in der Reihenfolge der Rueckseite`() {
        val frame = IntArray(phone3a.channels)
        frame[phone3a.of("C2")!!.index] = 1000
        frame[phone3a.of("A1")!!.index] = 1000
        assertEquals(listOf("A1", "C2"), phone3a.codes(frame))
    }

    @Test
    fun `ein leerer Rahmen hat keine Kanaele`() {
        assertTrue(phone3a.lit(IntArray(phone3a.channels)).isEmpty())
        assertTrue(phone3a.lit(IntArray(0)).isEmpty())
    }

    @Test
    fun `ein zu kurzer Rahmen stuerzt nicht ab`() {
        val short = IntArray(3) { 100 }
        assertEquals(listOf("C1", "C2", "C3"), phone3a.lit(short).map(Channel::code))
    }

    // ------------------------------------------------------------ Erkennung

    @Test
    fun `jede Modellkennung findet ihr Geraet`() {
        assertEquals("24111", DeviceCatalog.forModel("A059")!!.key)
        // Die Pro traegt je Region eine Endung; das GDK vergleicht nur exakt.
        assertEquals("24111", DeviceCatalog.forModel("A059P")!!.key)
        assertEquals("23111", DeviceCatalog.forModel("A142")!!.key)
        assertEquals("23113", DeviceCatalog.forModel("A142P")!!.key)
        assertEquals("22111", DeviceCatalog.forModel("AIN065")!!.key)
        assertEquals("25111", DeviceCatalog.forModel("A069")!!.key)
        assertEquals("25131", DeviceCatalog.forModel("A009P")!!.key)
        assertNull(DeviceCatalog.forModel("A069P"))
        assertNull(DeviceCatalog.forModel("Pixel 9"))
    }

    @Test
    fun `ein Matrixgeraet wird erkannt und nicht angesteuert`() {
        val detection = DeviceDetector.of("A024")
        assertTrue(detection is Detection.Matrix)
        assertEquals("Phone (3)", (detection as Detection.Matrix).name)

        // "A069P" faengt nicht beim Praefix des Phone (4a) ab.
        val pro = DeviceDetector.of("A069P")
        assertTrue(pro is Detection.Matrix)
        assertEquals("Phone (4a) Pro", (pro as Detection.Matrix).name)
    }

    @Test
    fun `ein unbekanntes Modell wird gemeldet`() {
        val detection = DeviceDetector.of("Pixel 9")
        assertTrue(detection is Detection.Unknown)
        assertEquals("Pixel 9", (detection as Detection.Unknown).model)
    }

    @Test
    fun `das erkannte Geraet traegt seine eigene Tabelle`() {
        val detection = DeviceDetector.of("A059")
        assertTrue(detection is Detection.Supported)
        assertEquals(36, (detection as Detection.Supported).spec.channels)
    }

    @Test
    fun `ein Geraet ohne Kanaele ist leer`() {
        val empty: DeviceSpec = DeviceSpec.unsupported("Pixel 9")
        assertEquals(0, empty.channels)
        assertTrue(empty.all.isEmpty())
        assertTrue(empty.groups.isEmpty())
        assertEquals(0, empty.frame(listOf("A1"), Level.value(100)).size)
    }
}
