package com.steimerbyte.glyphorchestra.glyph

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.Detection
import com.steimerbyte.glyphorchestra.device.sdkCode

/**
 * Die echte Ausgabe: spricht den Glyph-Dienst von Nothing OS ueber das
 * offizielle Glyph Developer Kit an.
 *
 * Warum `setFrameColors(int[])` und nicht `toggle(GlyphFrame)`: das Array ist
 * die einzige Schnittstelle, die einen Pegel **je Kanal** traegt. `toggle`
 * setzt jeden gewaehlten Kanal auf denselben Wert (4000) und ist damit fuer
 * "eine LED einzeln, gern auch dunkler" zu grob.
 *
 * Das Array hat so viele Stellen, wie das erkannte Geraet Kanaele hat -- die
 * Zahl steht in [DeviceSpec.channels] und wird hier nicht geraten. Ein Rahmen
 * fuer ein anderes Geraet wuerde die falschen LEDs ansprechen.
 *
 * Lebenszyklus, den das SDK verlangt: init -> register -> openSession, und
 * nur solange die App im Vordergrund ist. Deshalb schliesst die Activity die
 * Sitzung in onStop; ein Dienst oder Hintergrundbetrieb waere hier nicht
 * erlaubt.
 */
class NothingGlyph(
    context: Context,
    private val spec: DeviceSpec,
    private val services: () -> Service? = {
        runCatching { GlyphManager.getInstance(context.applicationContext) }
            .getOrNull()
            ?.let(::Ketchum)
    },
) : GlyphOutput {

    /**
     * Die Aufrufe, die diese App vom Glyph-Dienst braucht.
     *
     * Eine Naht, keine Abstraktion: `GlyphManager` hat einen privaten
     * Konstruktor und einen statischen Singleton, laesst sich also nicht
     * nachbauen. Ohne sie waere der Lebenszyklus -- Sitzung, gemerkter Rahmen,
     * Reihenfolge beim Wiederverbinden -- nur am Geraet pruefbar.
     */
    interface Service {
        fun init(callback: GlyphManager.Callback)
        fun unInit()
        fun register(code: String): Boolean
        fun openSession()
        fun closeSession()
        fun setFrameColors(values: IntArray)
        fun turnOff()
    }

    class Ketchum(private val manager: GlyphManager) : Service {
        override fun init(callback: GlyphManager.Callback) = manager.init(callback)
        override fun unInit() = manager.unInit()
        override fun register(code: String): Boolean = manager.register(code)
        override fun openSession() = manager.openSession()
        override fun closeSession() = manager.closeSession()
        override fun setFrameColors(values: IntArray) = manager.setFrameColors(values)
        override fun turnOff() = manager.turnOff()
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var manager: Service? = null
    private var bound = false
    private var authorized = false
    private var session = false
    private var pending: IntArray? = null

    /** Letzter gemeldeter Fehler, damit ein 20-Hz-Takt das Protokoll nicht flutet. */
    private var lastError: String? = null
    private var lastErrorAt = 0L

    override var onStatus: ((GlyphStatus) -> Unit)? = null

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName) = onConnected()

        override fun onServiceDisconnected(componentName: ComponentName) {
            session = false
            authorized = false
            emit(
                GlyphStatus(
                    GlyphStatus.Tone.FAIL,
                    "DIENST GETRENNT",
                    "Die Verbindung zum Glyph-Dienst von Nothing OS ist abgebrochen. " +
                        "Die App muss neu gestartet werden.",
                )
            )
        }
    }

    override fun connect() {
        if (bound) {
            if (session) publishReady()
            return
        }
        val instance = services()
        if (instance == null) {
            emit(
                GlyphStatus(
                    GlyphStatus.Tone.FAIL,
                    "KEIN DIENST",
                    "GlyphManager.getInstance() ist gescheitert. Auf diesem Geraet fehlt " +
                        "der Glyph-Dienst von Nothing OS.",
                )
            )
            return
        }
        manager = instance
        bound = true
        emit(GlyphStatus(GlyphStatus.Tone.WAIT, "VERBINDE", "Glyph-Dienst wird gebunden."))
        runCatching { instance.init(callback) }.onFailure {
            bound = false
            manager = null
            emit(fail("BINDEN GESCHEITERT", it))
        }
    }

    override fun disconnect() {
        if (session) {
            runCatching { manager?.closeSession() }
                .onFailure { Log.w(TAG, "closeSession: ${it.message}") }
        }
        if (bound) {
            runCatching { manager?.unInit() }
                .onFailure { Log.w(TAG, "unInit: ${it.message}") }
        }
        session = false
        authorized = false
        bound = false
        manager = null
        lastError = null
    }

    override fun apply(values: IntArray) {
        val snapshot = values.copyOf(spec.channels)
        val instance = manager
        if (!session || instance == null) {
            // Frueher Tipp, noch keine Sitzung: merken statt verwerfen.
            pending = snapshot
            return
        }
        runCatching { instance.setFrameColors(snapshot) }.onFailure { note("setFrameColors", it) }
    }

    override fun off() {
        val instance = manager
        // Ohne Sitzung gibt es nichts zu schalten -- dann ist die Null der
        // gemerkte Wunsch. Mit Sitzung wird sie gesendet und **nicht**
        // gemerkt: sonst ueberschriebe sie nach dem Wiederverbinden den
        // Rahmen, den die App beim READY gerade wieder gesetzt hat.
        if (!session || instance == null) {
            pending = IntArray(spec.channels)
            return
        }
        runCatching { instance.turnOff() }.onFailure { note("turnOff", it) }
    }

    private fun onConnected() {
        val instance = manager ?: return

        // Ueber den Detector, nicht direkt ueber Build.MODEL: derselbe Weg
        // wie die Activity, und ohne Geraet pruefbar.
        val detection = DeviceDetector.detect()
        if (detection !is Detection.Supported || detection.spec.key != spec.key) {
            emit(
                GlyphStatus(
                    GlyphStatus.Tone.FAIL,
                    "ANDERES GERAET",
                    "Gemeldetes Modell: ${Build.MODEL} (${Build.DEVICE}). Erwartet wurde " +
                        "'${spec.constant}' fuer ${spec.model}.",
                )
            )
            return
        }

        authorized = runCatching { instance.register(spec.constant) }.getOrDefault(false)
        if (!authorized) {
            emit(
                GlyphStatus(
                    GlyphStatus.Tone.WARN,
                    "NICHT FREIGEGEBEN",
                    "register() wurde abgelehnt. Nothing OS prueft den API-Schluessel " +
                        "aus dem Manifest (NothingKey): auf Android 16 entfaellt die " +
                        "Pruefung, auf aelteren Staenden hilft nur ein eigener Schluessel " +
                        "oder der Debug-Modus per adb.",
                )
            )
            return
        }

        val opened = runCatching { instance.openSession() }
        if (opened.isFailure) {
            emit(fail("SITZUNG GESCHEITERT", opened.exceptionOrNull()))
            return
        }
        session = true

        verifyChannelMap().takeIf { it.isNotEmpty() }?.let { mismatches ->
            Log.w(TAG, "Kanaltabelle weicht ab: $mismatches")
            emit(
                GlyphStatus(
                    GlyphStatus.Tone.WARN,
                    "KANALTABELLE ABWEICHEND",
                    "Die Bibliothek zaehlt anders als diese App (${mismatches.first()}). " +
                        "Gesendet wird der Bibliotheksindex; die Beschriftung kann " +
                        "dadurch verschoben sein.",
                )
            )
        }

        // Erst den gemerkten Rahmen, dann READY: die App setzt beim READY ihren
        // Betrieb fort und sendet dabei einen frischen Rahmen. Andersherum
        // gewaenne die alte Null -- die Rueckseite bliebe nach der Rueckkehr
        // aus dem Hintergrund dunkel, waehrend die Leiste "AN" zeigte.
        pending?.let { apply(it) }
        pending = null
        publishReady()
    }

    private fun publishReady() {
        emit(
            GlyphStatus(
                GlyphStatus.Tone.READY,
                "VERBUNDEN",
                "${spec.constant} · ${spec.model} · ${spec.channels} Kanaele · Sitzung offen",
            )
        )
    }

    /**
     * Vergleicht die selbst gepflegte Tabelle mit den Konstanten der
     * Bibliothek. Der Feldname `A_1` wird dabei zu `A1` -- genau der Code aus
     * [com.steimerbyte.glyphorchestra.device.Channel.code], siehe [sdkCode].
     * Abweichungen werden gemeldet, nicht verschluckt.
     */
    private fun verifyChannelMap(): List<String> {
        val problems = ArrayList<String>()
        val constants = runCatching {
            Class.forName("${Glyph::class.java.name}\$Code_${spec.codeClass}")
        }.getOrNull() ?: return problems
        for (field in constants.fields) {
            val code = sdkCode(field.name)
            val expected = spec.of(code)?.index ?: continue
            val actual = runCatching { field.getInt(null) }.getOrNull() ?: continue
            if (actual != expected) {
                problems += "$code: App $expected, Bibliothek $actual"
            }
        }
        return problems
    }

    private fun note(call: String, error: Throwable) {
        Log.w(TAG, "$call: ${error.message}")
        val text = error.message ?: error::class.java.simpleName
        val now = System.currentTimeMillis()
        if (text == lastError && now - lastErrorAt < ERROR_QUIET_MS) return
        lastError = text
        lastErrorAt = now
        emit(GlyphStatus(GlyphStatus.Tone.WARN, "SENDEFEHLER", "$call: $text"))
    }

    private fun fail(headline: String, error: Throwable?): GlyphStatus = GlyphStatus(
        GlyphStatus.Tone.FAIL,
        headline,
        error?.message ?: error?.javaClass?.simpleName ?: "Unbekannter Fehler.",
    )

    private fun emit(status: GlyphStatus) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStatus?.invoke(status)
        } else {
            main.post { onStatus?.invoke(status) }
        }
    }

    private companion object {
        const val TAG = "GlyphDeck"
        const val ERROR_QUIET_MS = 4000L
    }
}
