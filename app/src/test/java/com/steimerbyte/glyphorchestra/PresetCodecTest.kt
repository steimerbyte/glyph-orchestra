package com.steimerbyte.glyphorchestra

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.model.Preset
import com.steimerbyte.glyphorchestra.model.PresetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Das Textformat der Presets -- der Codec ist die einzige Stelle, die es kennt. */
class PresetCodecTest {

    private val spec: DeviceSpec = DeviceCatalog.PHONE_3A

    @Test
    fun `ein Preset ueberlebt hin und zurueck`() {
        val presets = listOf(
            Preset("RING", listOf("A1", "A2", "B1")),
            Preset("MITTE", listOf("C7", "C8")),
        )
        val decoded = PresetCodec.decode(PresetCodec.encode(presets, spec), spec)
        assertEquals(presets, decoded)
    }

    @Test
    fun `leerer Text ergibt eine leere Liste`() {
        assertTrue(PresetCodec.decode(null, spec).isEmpty())
        assertTrue(PresetCodec.decode("", spec).isEmpty())
        assertTrue(PresetCodec.decode(PresetCodec.HEADER, spec).isEmpty())
    }

    @Test
    fun `unbekannte Kanaele fallen weg`() {
        val text = PresetCodec.HEADER + "\nMIX\tA1,Z9,C20"
        val decoded = PresetCodec.decode(text, spec)
        assertEquals(listOf(Preset("MIX", listOf("A1", "C20"))), decoded)
    }

    @Test
    fun `ein Preset ohne gueltigen Kanal wird nicht gespeichert`() {
        assertEquals(
            emptyList<Preset>(),
            PresetCodec.decode(PresetCodec.HEADER + "\nLEER\tZ9", spec),
        )
        assertEquals(
            emptyList<Preset>(),
            PresetCodec.decode(
                PresetCodec.encode(listOf(Preset("LEER", listOf("Z9"))), spec),
                spec,
            ),
        )
    }

    @Test
    fun `ein doppelter Name ersetzt`() {
        val text = PresetCodec.HEADER + "\nrot\tA1\nrot\tB1"
        val decoded = PresetCodec.decode(text, spec)
        assertEquals(1, decoded.size)
        assertEquals(listOf("B1"), decoded.single().codes)
    }

    @Test
    fun `gleicher Name in anderer Schreibweise ersetzt`() {
        val text = PresetCodec.HEADER + "\nRot\tA1\nrot\tB1"
        val decoded = PresetCodec.decode(text, spec)
        assertEquals(1, decoded.size)
        assertEquals("rot", decoded.single().name)
        assertEquals(listOf("B1"), decoded.single().codes)
    }

    @Test
    fun `der Name wird bereinigt`() {
        assertEquals("zwei Woerter", PresetCodec.sanitizeName("  zwei   Woerter  "))
        assertEquals("keine Tabs", PresetCodec.sanitizeName("keine\tTabs"))
        assertEquals(24, PresetCodec.sanitizeName("x".repeat(60)).length)
        assertEquals("", PresetCodec.sanitizeName("   \t  "))
    }

    @Test
    fun `Kanaele werden nicht doppelt gespeichert`() {
        val encoded = PresetCodec.encode(
            listOf(Preset("DOPPELT", listOf("A1", "A1", "B1"))),
            spec,
        )
        assertEquals(listOf("A1", "B1"), PresetCodec.decode(encoded, spec).single().codes)
    }

    @Test
    fun `Zeilen ohne Tabulator werden ignoriert`() {
        val text = PresetCodec.HEADER + "\nkaputt\nOK\tA1"
        assertEquals(listOf(Preset("OK", listOf("A1"))), PresetCodec.decode(text, spec))
    }

    @Test
    fun `Kanaele werden als Index aufgeloest`() {
        val preset = Preset("X", listOf("C1", "A11", "B5"))
        assertEquals(listOf(0, 30, 35), preset.channels(spec).map { it.index })
        assertEquals(3, preset.size(spec))
    }

    @Test
    fun `ein unbekannter Code macht den Vorgabenkanal nicht kaputt`() {
        val preset = Preset("X", listOf("A1", "Z7"))
        assertEquals(1, preset.size(spec))
        assertEquals("A1", preset.channels(spec).single().code)
    }

    @Test
    fun `alle Vorgaben sind gueltig und verschieden`() {
        val all = spec.all.map { it.code }
        assertEquals(36, all.size)
        assertEquals(36, all.toSet().size)
    }

    @Test
    fun `ein fremdes Geraet verliert seine Kanaele`() {
        // Ein Preset vom Phone (2) hat auf dem Phone (4a) nur A1..A6.
        val phone2 = DeviceCatalog.PHONE_2
        val preset = Preset("PHONE2", phone2.all.map { it.code })
        assertEquals(33, preset.size(phone2))
        // Das (4a) hat nur A1..A6; vom Phone (2)-Satz ueberleben A1 und A2.
        assertEquals(listOf("A1", "A2"), preset.channels(DeviceCatalog.PHONE_4A).map { it.code })

        val kept = Preset("MIX", listOf("A1", "A6", "C1_16", "D1_8"))
        assertEquals(listOf("A1", "A6"), kept.channels(DeviceCatalog.PHONE_4A).map { it.code })
        assertEquals(3, kept.size(phone2))
    }
}
