package com.steimerbyte.glyphorchestra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.steimerbyte.glyphorchestra.audio.AnalysisResult
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.glyph.GlyphOutput
import com.steimerbyte.glyphorchestra.glyph.GlyphProvider
import com.steimerbyte.glyphorchestra.glyph.GlyphStatus
import com.steimerbyte.glyphorchestra.ui.AnalysisRunner
import com.steimerbyte.glyphorchestra.ui.BandChartView
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Die Zustaende der Oberflaeche: ohne Datei gesperrt, nach der Analyse
 * abspielbar, beim Verlassen getrennt.
 *
 * Zwei Naehte machen das ohne Geraet und ohne Datei moeglich: [GlyphProvider]
 * liefert eine Ausgabe, die nur mitschreibt, und [AnalysisRunner] eine feste
 * Antwort. Beide sind im Produktionspfad echte Implementierungen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    private val uri: Uri = Uri.parse("content://test/track.mp3")
    private var connected = false
    private var defaultFactory = GlyphProvider.factory
    private var defaultRunner = AnalysisRunner.run

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("glyph-orchestra", Context.MODE_PRIVATE)
            .edit().clear().commit()

        DeviceDetector.model = { "A059" }
        GlyphProvider.factory = { _, _ ->
            object : GlyphOutput {
                override var onStatus: ((GlyphStatus) -> Unit)? = null

                override fun connect() {
                    connected = true
                    onStatus?.invoke(GlyphStatus(GlyphStatus.Tone.READY, "VERBUNDEN", "Sitzung offen"))
                }

                override fun disconnect() {
                    connected = false
                }

                override fun apply(values: IntArray) = Unit

                override fun off() = Unit
            }
        }
        AnalysisRunner.run = { _, _, _, _, progress, _ ->
            progress(1.0)
            analysis()
        }
    }

    @After
    fun tearDown() {
        GlyphProvider.factory = defaultFactory
        AnalysisRunner.run = defaultRunner
        DeviceDetector.reset()
    }

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().apply {
            background = Dispatchers.Unconfined
        }

    private fun MainActivity.play(): Button = findViewById(R.id.play_button)

    private fun MainActivity.analyze(): Button = findViewById(R.id.analyze_button)

    @Test
    fun `ohne Datei sind Analysieren und Abspielen gesperrt`() {
        val activity = activity()
        assertFalse(activity.analyze().isEnabled)
        assertFalse(activity.play().isEnabled)
    }

    @Test
    fun `eine gewaehlte Datei gibt Analysieren frei`() {
        val activity = activity()
        activity.selectFile(uri)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.analyze().isEnabled)
        assertFalse(activity.play().isEnabled)
    }

    @Test
    fun `nach der Analyse laesst sich abspielen`() {
        val activity = activity()
        activity.selectFile(uri)
        activity.background = Dispatchers.Unconfined
        activity.analyze().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.play().isEnabled)
        assertTrue(activity.analyze().isEnabled)

        val facts = activity.findViewById<TextView>(R.id.facts).text.toString()
        assertTrue(facts.contains("128"))
        assertTrue(facts.contains("4/4"))
        assertEquals(
            "ANALYSE FERTIG · 30 BILDER",
            activity.findViewById<TextView>(R.id.progress_line).text.toString(),
        )
    }

    @Test
    fun `nach dem Abspielen ist Analysieren wieder frei`() {
        val activity = activity()
        activity.selectFile(uri)
        activity.background = Dispatchers.Unconfined
        activity.analyze().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        activity.play().performClick()
        assertEquals("STOPP", activity.play().text.toString())
        shadowOf(Looper.getMainLooper()).idle()

        activity.play().performClick()
        assertEquals("ABSPIELEN", activity.play().text.toString())
    }

    @Test
    fun `eine neue Datei nimmt Analyse und Freigabe zurueck`() {
        val activity = activity()
        activity.selectFile(uri)
        activity.background = Dispatchers.Unconfined
        activity.analyze().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.play().isEnabled)

        activity.selectFile(Uri.parse("content://test/other.mp3"))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.play().isEnabled)
        assertEquals(
            "BEREIT",
            activity.findViewById<TextView>(R.id.progress_line).text.toString(),
        )
    }

    @Test
    fun `der Verbindungszustand folgt dem Lebenszyklus`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get().apply { background = Dispatchers.Unconfined }
        val state = activity.findViewById<TextView>(R.id.connection_state)
        assertEquals("VERBUNDEN", state.text.toString())
        assertTrue(connected)

        controller.pause().stop()
        assertEquals("GETRENNT", state.text.toString())
        assertFalse(connected)
    }

    @Test
    fun `ein anderes Geraet bekommt keine Wiedergabe`() {
        DeviceDetector.model = { "A024" }
        val activity = activity()
        activity.selectFile(uri)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.play().isEnabled)
        assertEquals(
            "NICHT UNTERSTÜTZT",
            activity.findViewById<TextView>(R.id.connection_state).text.toString(),
        )
    }

    @Test
    fun `das Diagramm zeichnet Raster, Beats und Zeiger`() {
        val activity = activity()
        activity.selectFile(uri)
        activity.analyze().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val chart = activity.findViewById<BandChartView>(R.id.band_chart)
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY),
        )
        chart.layout(0, 0, 400, 140)
        chart.setPlayhead(0.5f)

        val frame = Bitmap.createBitmap(400, 140, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(frame))
        assertTrue(frame.getPixel(10, 10) != Color.BLACK)
    }

    @Test
    fun `die About-Sektion nennt Leitspruch, Version und SDK-Aufrufe`() {
        val text = activity().aboutText().toString()
        assertTrue(text.contains("Function over form."))
        assertTrue(text.contains("Version ${BuildConfig.VERSION_NAME}"))
        assertTrue(text.contains("GlyphManager.getInstance(context)"))
    }

    /** Winzige, aber vollstaendige Antwort -- der Chart rechnet damit wirklich. */
    private fun analysis(): AnalysisResult = AnalysisResult(
        fpsDraw = 30,
        frames = 30,
        levels = IntArray(30 * 36),
        tension = FloatArray(30),
        bandsDb = FloatArray(30 * 17) { -60f + it % 17 },
        fpsAnalysis = 86.13,
        bpm = 128.0,
        beats = doubleArrayOf(0.5, 1.0),
        meter = "4/4",
        confidence = 0.87,
        durationSec = 1.0,
    )
}
