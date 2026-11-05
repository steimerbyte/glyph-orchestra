package com.steimerbyte.glyphorchestra.device

/**
 * Ein einzeln adressierbarer Kanal: Position im SDK-Array und sein Code.
 *
 * [index] ist der Wert, den das Glyph Developer Kit in seinen `Code_*`-
 * Klassen fuehrt; nur er geht an `setFrameColors`. [code] ist die
 * Beschriftung, die der Nutzer liest ("A1", "C1_7", "D1_8"). Beides gehoert
 * zusammen, weil ein stiller Versatz sonst die falsche LED anspricht.
 *
 * [group] ist die grobe Gliederung fuer die Gruppen-Schalter -- auf dem
 * Phone (2) gibt es zehn Codes, aber nur fuenf Schalter (A, B, C, D, E).
 */
data class Channel(val index: Int, val group: String, val code: String)

/** Form eines Streifens im Einheitsquadrat: x nach rechts, y nach unten. */
sealed interface StripShape {

    /**
     * Bogen um ([cx], [cy]) mit [radius] (Anteil der halben Kante). Winkel in
     * Grad im Uhrzeigersinn ab 12 Uhr; ein negativer [sweep] laeuft
     * gegen den Uhrzeigersinn -- so beschreibt das Phone (2) seinen Bogen,
     * der unten rechts beginnt und oben links endet.
     */
    data class Arc(
        val start: Float,
        val sweep: Float,
        val radius: Float = 1f,
        val cx: Float = 0.5f,
        val cy: Float = 0.5f,
    ) : StripShape

    /** Gerade von ([x0], [y0]) nach ([x1], [y1]). */
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : StripShape
}

/**
 * Ein Streifen: [count] Kanaele ab [firstIndex], verteilt ueber [shape].
 *
 * [label] ist der Namensstamm aus dem SDK, [numbered] sagt, ob die laufende
 * Nummer angehaengt wird. Beides ist noetig, weil das SDK es nicht einheitlich
 * haelt: auf dem Phone (2a) heissen die beiden Einzelkanäle "A" und "B", auf
 * dem Phone (3a) dagegen "A1".."B5". Die Regel steht in [code].
 */
data class Strip(
    val group: String,
    val label: String,
    val firstIndex: Int,
    val count: Int,
    val shape: StripShape,
    val numbered: Boolean = true,
) {

    val codes: List<String> = (1..count).map(::code)

    /**
     * Der Code des [number]-ten Kanals -- nach der Schreibweise des SDK:
     * `C` -> `C7`, `C1` -> `C1_7`, und ein Einzelkanal mit Ziffer im Stamm
     * bleibt ohne Nummer (`C2`, nicht `C2_1`).
     */
    private fun code(number: Int): String = when {
        !numbered -> label
        count == 1 && label.last().isDigit() -> label
        label.last().isDigit() -> "${label}_$number"
        else -> "$label$number"
    }
}

/**
 * Der Kanalcode zu einem Feldnamen des SDK -- die Umkehrung der Regel in
 * [Strip.code].
 *
 * Das SDK schreibt `C_1` auf dem Phone (2a), aber `C1_7` auf dem Phone (2)
 * und `A1` auf dem Phone (1): die laufende Nummer haengt nur dann mit
 * Unterstrich am Stamm, wenn der Stamm selbst schon eine Ziffer traegt.
 * [Strip.code] erzeugt daraus `C1`, `C1_7`, `A1`; dieselbe Funktion macht aus
 * dem Feldnamen wieder denselben Code zurueck. Ohne sie wuerde `D1_1` zu `D11`
 * und damit zu einem Kanal, den es nicht gibt.
 */
fun sdkCode(fieldName: String): String {
    val match = STEM.matchEntire(fieldName) ?: return fieldName
    return match.groupValues[1] + match.groupValues[2]
}

private val STEM = Regex("^([A-Za-z]+)_(\\d+)$")

/**
 * Ein Geraet, wie das GDK es kennt: Kanalzahl, Kanalcodes und die Lage der
 * Streifen auf der Rueckseite.
 *
 * Die Kanalcodes und -indizes stammen aus den `Code_*`-Klassen der AAR und
 * werden im Test dagegen geprueft. Nur die Geografie ([strips]) ist Handarbeit
 * -- und bei Geraeten, die hier nie auf dem Tisch lagen, ausdruecklich eine
 * Skizze, siehe [measured].
 */
data class DeviceSpec(
    val key: String,
    val constant: String,
    val model: String,
    val strips: List<Strip>,
    /** Wahre Kanalzahl laut SDK; bei einer Skizze die einzige feste Groesse. */
    val channels: Int,
    /** Weitere Modellkennungen, die dasselbe Geraet meinen. */
    val aliases: List<String> = emptyList(),
    /** Name der `Code_*`-Klasse, falls sie anders heisst als [key]. */
    val codeClass: String = key,
    /** Am Geraet vermessen -- sonst Skizze nach dem Schaubild des GDK. */
    val measured: Boolean = false,
) {

    /** Alle Kanaele in der Reihenfolge der Rueckseite, nicht nach Index. */
    val all: List<Channel> = strips.flatMap { strip ->
        strip.codes.mapIndexed { offset, code ->
            Channel(strip.firstIndex + offset, strip.group, code)
        }
    }

    /** Die Gruppen-Schalter in Anzeigereihenfolge. */
    val groups: List<String> = strips.map { it.group }.distinct()

    private val byCode: Map<String, Channel> = all.associateBy { it.code }

    fun of(code: String): Channel? = byCode[code]

    fun group(letter: String): List<Channel> = all.filter { it.group == letter }

    fun channelsOf(codes: Collection<String>): List<Channel> = codes.mapNotNull(byCode::get)

    fun lit(values: IntArray): List<Channel> =
        all.filter { it.index in values.indices && values[it.index] > 0 }

    fun codes(values: IntArray): List<String> = lit(values).map { it.code }

    /** Rahmen aus Codes; unbekannte Codes fallen weg, wie beim Speichern. */
    fun frame(codes: Collection<String>, level: Int): IntArray {
        val next = IntArray(channels)
        for (channel in channelsOf(codes)) next[channel.index] = level
        return next
    }

    companion object {

        /**
         * Ein Geraet, das diese App nicht anspricht: keine Kanaele, keine
         * Streifen. Damit braucht die Oberflaeche keinen Sonderpfad mit
         * `null`, sie zeigt nur nichts an.
         */
        fun unsupported(model: String): DeviceSpec = DeviceSpec(
            key = "unknown",
            constant = model,
            model = model,
            strips = emptyList(),
            channels = 0,
        )
    }
}
