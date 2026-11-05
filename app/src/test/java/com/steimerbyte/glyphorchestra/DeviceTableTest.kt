package com.steimerbyte.glyphorchestra

import com.nothing.ketchum.Glyph
import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.device.sdkCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die eigenen Kanaltabellen gegen die Konstanten des Glyph Developer Kit.
 *
 * `NothingGlyph` prueft das auf dem Geraet und meldet Abweichungen in der
 * Statuskarte. Hier faellt derselbe Fehler schon beim Bauen auf -- ohne
 * Geraet, weil `Glyph.Code_*` nur Zahlen haelt.
 */
class DeviceTableTest {

    @Test
    fun `jedes Geraet hat so viele Kanaele wie die Bibliothek`() {
        for (spec in DeviceCatalog.segment) {
            val name = sizeConstant(spec.key)
            val size = field(name).getInt(null)
            assertEquals("${spec.model}: $name", spec.channels, size)
            assertEquals("${spec.model}: Felder", spec.channels, constants(spec.codeClass).size)
        }
    }

    @Test
    fun `jeder Bibliotheksindex passt zur App`() {
        for (spec in DeviceCatalog.segment) {
            val seen = HashSet<Int>()
            for (field in constants(spec.codeClass)) {
                val code = sdkCode(field.name)
                val libraryIndex = field.getInt(null)
                val channel = spec.of(code)
                assertNotNull("${spec.model} kennt $code nicht (Bibliothek: $libraryIndex)", channel)
                assertEquals(
                    "Index fuer $code auf ${spec.model}",
                    libraryIndex,
                    channel!!.index,
                )
                assertTrue("Index $libraryIndex doppelt auf ${spec.model}", seen.add(libraryIndex))
            }
            assertEquals(
                "${spec.model}: nicht alle Indizes belegt",
                (0 until spec.channels).toSet(),
                seen,
            )
        }
    }

    @Test
    fun `die Modellkennung passt zur Bibliothek`() {
        for (spec in DeviceCatalog.segment) {
            val library = field("DEVICE_${spec.key}").get(null)
            assertEquals("${spec.model}: DEVICE_${spec.key}", spec.constant, library)
        }
    }

    /** `Code_23113` gibt es nicht: das (2a) Plus teilt die Tabelle der (2a). */
    private fun constants(codeClass: String): Array<java.lang.reflect.Field> =
        Class.forName("${Glyph::class.java.name}\$Code_$codeClass").fields

    /**
     * Die `DEVICE_*`-Konstanten sind teils paketprivat (`static int
     * DEVICE_24111_SIZE`), also nicht ueber [Class.getField] erreichbar --
     * `getDeclaredField` plus `isAccessible` liefert sie trotzdem.
     */
    private fun field(name: String): java.lang.reflect.Field {
        val found = Glyph::class.java.getDeclaredField(name)
        found.isAccessible = true
        return found
    }

    /** Auch `DEVICE_23113_SIZE` fehlt; dort gilt die Groesse des (2a). */
    private fun sizeConstant(key: String): String =
        if (key == "23113") "DEVICE_23111_SIZE" else "DEVICE_${key}_SIZE"
}
