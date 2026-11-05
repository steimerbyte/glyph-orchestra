package com.steimerbyte.glyphorchestra.model

import android.content.Context
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.device.Detection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Der Tonschalter in der Datei.
 *
 * Warum das geprueft wird: der Schalter ist die einzige Einstellung, die einen
 * Neustart ueberlebt, und er liegt in derselben Datei wie die Presets. Zwei
 * Stellen mit demselben Wert verschieden zu lesen ist genau der Fehler, den
 * [Settings.PREFS] verhindern soll -- und der Test belegt, dass beide Seiten
 * wirklich dieselbe Datei meinen.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        DeviceDetector.reset()
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `ohne gespeicherten Wert sind die Toene an`() {
        val settings = Settings.load(
            context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        )

        assertTrue(settings.sound)
    }

    @Test
    fun `ein abgeschalteter Ton ueberlebt den Neustart`() {
        Settings(sound = false).save(context)

        val loaded = Settings.load(
            context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        )

        assertFalse(loaded.sound)
    }

    @Test
    fun `die Presets und der Tonschalter liegen in derselben Datei`() {
        DeviceDetector.model = { "A059" }
        Settings(sound = false).save(context)
        val detection = DeviceDetector.detect() as Detection.Supported
        val store = PresetStore(context, detection.spec)
        val latch = CountDownLatch(1)
        var seen: Settings? = null

        store.loadAsync { library ->
            seen = library.settings
            latch.countDown()
        }

        assertTrue("Laden lief nicht durch", latch.await(5, TimeUnit.SECONDS))
        assertEquals(false, seen?.sound)
        store.shutdown()
    }
}
