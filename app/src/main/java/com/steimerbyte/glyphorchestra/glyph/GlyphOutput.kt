package com.steimerbyte.glyphorchestra.glyph

/**
 * Was die Oberflaeche ueber den Glyph-Zugang anzeigt.
 *
 * [detail] ist der Satz, der im Fehlerfall die Ursache nennt -- "geht nicht"
 * ohne Grund waere in dieser App besonders schlimm, weil der Nutzer sonst
 * nicht unterscheiden kann, ob die App, das Modell oder eine fehlende
 * Freigabe schuld ist.
 */
data class GlyphStatus(val tone: Tone, val headline: String, val detail: String) {

    enum class Tone { WAIT, READY, WARN, FAIL }
}

/**
 * Der einzige Weg der Oberflaeche zum Glyph Interface.
 *
 * Ein Interface statt direkter SDK-Aufrufe, weil die Klassen aus der AAR auf
 * der JVM nicht ladbar sind: nur so laesst sich ohne Geraet pruefen, welche
 * Pegel die Oberflaeche tatsaechlich abschickt und was beim Ausschalten
 * passiert. Die echte Implementierung ist [NothingGlyph].
 */
interface GlyphOutput {

    var onStatus: ((GlyphStatus) -> Unit)?

    /** Bindet den Glyph-Dienst und oeffnet eine Sitzung. */
    fun connect()

    /** Schliesst die Sitzung und loest die Bindung. */
    fun disconnect()

    /**
     * Schickt einen Pegel je Kanal (0 = aus) an das Geraet -- so viele Stellen,
     * wie das erkannte Geraet Kanaele hat. Aufrufe vor einer offenen Sitzung
     * werden gemerkt und beim Verbinden nachgeholt, damit ein frueher Tipp
     * nicht verloren geht.
     */
    fun apply(values: IntArray)

    /** Alle LEDs aus, Sitzung bleibt offen. */
    fun off()
}

/**
 * Testnaht: die Activity fragt hier nach ihrer Ausgabe.
 *
 * Kein Testhaken im Produktionspfad -- die Oberflaeche kennt nur das
 * Interface, und im Test wird die Fabrik ersetzt. Dieselbe Bauform wie ein
 * einspeisbarer Ladepfad bei Dateizugriffen.
 */
object GlyphProvider {

    var factory: (android.content.Context, com.steimerbyte.glyphorchestra.device.DeviceSpec) -> GlyphOutput =
        { context, spec -> NothingGlyph(context, spec) }

    fun create(
        context: android.content.Context,
        spec: com.steimerbyte.glyphorchestra.device.DeviceSpec,
    ): GlyphOutput = factory(context, spec)
}
