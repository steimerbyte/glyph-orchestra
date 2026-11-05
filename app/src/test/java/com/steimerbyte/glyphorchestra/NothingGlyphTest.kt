package com.steimerbyte.glyphorchestra

import com.nothing.ketchum.GlyphManager
import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.glyph.GlyphStatus
import com.steimerbyte.glyphorchestra.glyph.NothingGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Der Lebenszyklus der echten Ausgabe gegen einen nachgebauten Dienst.
 *
 * `GlyphManager` selbst laesst sich nicht nachbauen (privater Konstruktor,
 * statischer Singleton), deshalb die [NothingGlyph.Service]-Naht. Geprueft
 * wird, was nur am Geraet auffiele: wann eine Sitzung offen ist, was mit einem
 * Rahmen passiert, der zu frueh kommt, und in welcher Reihenfolge ein
 * Wiederverbinden den gemerkten und den wieder aufgenommenen Rahmen sendet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NothingGlyphTest {

    private val spec = DeviceCatalog.PHONE_3A

    @Before
    fun setUp() {
        // Die Ausgabe prueft das gemeldete Modell gegen die eigene Tabelle;
        // ohne das Telefon meldet Robolectric ein fremdes Geraet.
        DeviceDetector.model = { spec.constant }
    }

    @After
    fun tearDown() {
        DeviceDetector.reset()
    }

    private class FakeService : NothingGlyph.Service {
        var authorize = true
        var openFails = false
        var callback: GlyphManager.Callback? = null
        val frames = ArrayList<IntArray>()
        var turnOffs = 0
        var closes = 0
        var uninits = 0
        var openSessions = 0

        override fun init(callback: GlyphManager.Callback) {
            this.callback = callback
        }

        override fun unInit() {
            uninits++
        }

        override fun register(code: String): Boolean = authorize

        override fun openSession() {
            if (openFails) throw IllegalStateException("keine Sitzung")
            openSessions++
        }

        override fun closeSession() {
            closes++
        }

        override fun setFrameColors(values: IntArray) {
            frames += values.copyOf()
        }

        override fun turnOff() {
            turnOffs++
        }

        fun fireConnected() = callback!!.onServiceConnected(ANDROID_COMPONENT)
    }

    private fun output(service: FakeService): NothingGlyph =
        NothingGlyph(RuntimeEnvironment.getApplication(), spec, { service })

    @Test
    fun `ohne Sitzung wird ein Rahmen gemerkt und beim Binden gesendet`() {
        val service = FakeService()
        val glyph = output(service)
        val statuses = ArrayList<GlyphStatus>()
        glyph.onStatus = { statuses += it }

        glyph.connect()
        val early = IntArray(spec.channels) { if (it == 3) 1200 else 0 }
        glyph.apply(early)
        assertTrue("vor der Sitzung gesendet", service.frames.isEmpty())

        service.fireConnected()

        assertEquals("gemerkter Rahmen nicht nachgeholt", 1, service.frames.size)
        assertEquals(1200, service.frames.first()[3])
        assertEquals(GlyphStatus.Tone.READY, statuses.last().tone)
    }

    /**
     * Der Fehler, der nur am Geraet auffiel: App in den Hintergrund, zurueck,
     * Betrieb steht auf AN -- und die Rueckseite blieb dunkel, weil die in
     * `onStop` gemerkte Null nach dem Wiederverbinden den gerade wieder
     * gesetzten Rahmen ueberschrieb.
     */
    @Test
    fun `nach dem Wiederverbinden gewinnt der wieder aufgenommene Rahmen`() {
        val service = FakeService()
        val glyph = output(service)
        val lit = IntArray(spec.channels) { if (it == 7) 4000 else 0 }
        // Wie die Activity: beim READY den eigenen Betrieb fortschreiben.
        glyph.onStatus = { if (it.tone == GlyphStatus.Tone.READY) glyph.apply(lit) }

        glyph.connect()
        service.fireConnected()
        glyph.apply(lit)
        assertEquals("stehender Rahmen nicht gesendet", 7, service.frames.last().indexOf(4000))

        // Hintergrund: abschalten und Sitzung schliessen.
        glyph.off()
        glyph.disconnect()
        assertEquals("turnOff nicht gerufen", 1, service.turnOffs)

        // Rueckkehr: neu binden. READY setzt den Rahmen, danach darf nichts
        // Altes mehr nachkommen.
        service.callback = null
        glyph.connect()
        service.fireConnected()

        val last = service.frames.last()
        assertEquals("letzter Rahmen ist die Null statt des Betriebs", 4000, last[7])
        assertEquals(1, last.count { it > 0 })
    }

    @Test
    fun `die Null wird gemerkt, wenn es keine Sitzung gibt`() {
        val service = FakeService()
        val glyph = output(service)
        glyph.connect()

        // Vor dem Binden abgeschaltet: der Wunsch ist "aus", nicht "der
        // vorherige Rahmen".
        glyph.apply(IntArray(spec.channels) { 4000 })
        glyph.off()
        service.fireConnected()

        assertEquals(1, service.frames.size)
        assertEquals("gemerkter Rahmen nicht dunkel", 0, service.frames.first().count { it > 0 })
        assertEquals("turnOff ohne Sitzung gerufen", 0, service.turnOffs)
    }

    @Test
    fun `ein abgelehntes register oeffnet keine Sitzung`() {
        val service = FakeService().apply { authorize = false }
        val glyph = output(service)
        val statuses = ArrayList<GlyphStatus>()
        glyph.onStatus = { statuses += it }

        glyph.connect()
        service.fireConnected()

        assertEquals(0, service.openSessions)
        assertEquals(GlyphStatus.Tone.WARN, statuses.last().tone)
        glyph.apply(IntArray(spec.channels) { 4000 })
        assertTrue("ohne Freigabe gesendet: ${service.frames.size}", service.frames.isEmpty())
    }

    @Test
    fun `das Verlassen schliesst die Sitzung`() {
        val service = FakeService()
        val glyph = output(service)
        glyph.connect()
        service.fireConnected()

        glyph.disconnect()

        assertEquals(1, service.closes)
        assertEquals(1, service.uninits)
    }

    private companion object {
        val ANDROID_COMPONENT = android.content.ComponentName("com.nothing.glyph", "GlyphService")
    }
}
