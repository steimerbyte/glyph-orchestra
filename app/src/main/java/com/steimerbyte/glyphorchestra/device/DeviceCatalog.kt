package com.steimerbyte.glyphorchestra.device

/**
 * Die Geraete, deren Glyph Interface aus einzelnen Segmenten besteht.
 *
 * Kanalcodes und -indizes stammen aus den `Code_*`-Klassen des Glyph
 * Developer Kit (AAR `glyph-matrix-sdk-2.0.aar`); [DeviceTableTest] haelt
 * beide Tabellen gegeneinander. Die **Lage** der Streifen ist Handarbeit:
 * beim Phone (3a) am Geraet vermessen, bei allen anderen eine Skizze nach dem
 * Schaubild des GDK -- dort steht [DeviceSpec.measured] auf `false`, und die
 * App sagt das auch. Genaue Eingabe ist ohnehin das Raster, nicht die Karte.
 *
 * Die Matrix-Geraete (Phone (3) 25x25, Phone (4a) Pro 13x13) stehen hier
 * nicht: sie werden ueber `setAppMatrixFrame` angesprochen, nicht ueber
 * Kanalzahlen. Sie werden erkannt und benannt, aber nicht angesteuert.
 */
object DeviceCatalog {

    /** Phone (1): 15 Kanaele, Ring um die Spule plus Punktreihe unten. */
    val PHONE_1 = DeviceSpec(
        key = "20111",
        constant = "A063",
        model = "Phone (1)",
        channels = 15,
        strips = listOf(
            Strip("A", "A", 0, 1, point(0.64f, 0.14f)),
            Strip("B", "B", 1, 1, point(0.34f, 0.84f)),
            Strip("C", "C", 2, 4, StripShape.Arc(start = 200f, sweep = 150f, radius = 0.58f)),
            Strip("E", "E", 6, 1, point(0.82f, 0.48f)),
            Strip("D", "D1", 7, 8, StripShape.Line(0.50f, 0.56f, 0.50f, 0.92f)),
        ),
    )

    /** Phone (2): 33 Kanaele -- der grosse Bogen C1 laeuft unten rechts nach oben links. */
    val PHONE_2 = DeviceSpec(
        key = "22111",
        constant = "A065",
        model = "Phone (2)",
        channels = 33,
        aliases = listOf("AIN065"),
        strips = listOf(
            Strip("A", "A", 0, 2, StripShape.Line(0.36f, 0.10f, 0.64f, 0.10f)),
            Strip("B", "B", 2, 1, point(0.50f, 0.23f)),
            Strip("C", "C1", 3, 16, StripShape.Arc(start = 135f, sweep = -180f)),
            Strip("C", "C2", 19, 1, StripShape.Arc(start = 200f, sweep = 11f)),
            Strip("C", "C3", 20, 1, StripShape.Arc(start = 218f, sweep = 11f)),
            Strip("C", "C4", 21, 1, StripShape.Arc(start = 236f, sweep = 11f)),
            Strip("C", "C5", 22, 1, StripShape.Arc(start = 254f, sweep = 11f)),
            Strip("C", "C6", 23, 1, StripShape.Arc(start = 272f, sweep = 11f)),
            Strip("E", "E", 24, 1, point(0.50f, 0.72f)),
            Strip("D", "D1", 25, 8, StripShape.Line(0.76f, 0.56f, 0.76f, 0.92f)),
        ),
    )

    /** Phone (2a): 26 Kanaele -- der Bogen C_1..C_24 laeuft unten links nach oben rechts. */
    val PHONE_2A = DeviceSpec(
        key = "23111",
        constant = "A142",
        model = "Phone (2a)",
        channels = 26,
        strips = listOf(
            Strip("C", "C", 0, 24, StripShape.Arc(start = 225f, sweep = 180f)),
            Strip("B", "B", 24, 1, StripShape.Line(0.60f, 0.86f, 0.74f, 0.86f), numbered = false),
            Strip("A", "A", 25, 1, StripShape.Line(0.26f, 0.86f, 0.40f, 0.86f), numbered = false),
        ),
    )

    /** Phone (2a) Plus: eigene Modellkennung, gleiche Kanaltabelle wie die (2a). */
    val PHONE_2A_PLUS = PHONE_2A.copy(
        key = "23113",
        constant = "A142P",
        model = "Phone (2a) Plus",
        codeClass = "23111",
    )

    /** Phone (3a) / (3a) Pro: 36 Kanaele, drei Boegen um das Kameramodul. */
    val PHONE_3A = DeviceSpec(
        key = "24111",
        constant = "A059",
        model = "Phone (3a) / (3a) Pro",
        channels = 36,
        strips = listOf(
            Strip("A", "A", 20, 11, StripShape.Arc(start = 40f, sweep = 90f)),
            Strip("B", "B", 31, 5, StripShape.Arc(start = 148f, sweep = 46f)),
            Strip("C", "C", 0, 20, StripShape.Arc(start = 202f, sweep = 169f)),
        ),
        // Der Ring ist am Geraet geprueft, nicht aus einem Schaubild geraten.
        measured = true,
    )

    /** Phone (4a): 6 Quadrate in einer senkrechten Reihe. */
    val PHONE_4A = DeviceSpec(
        key = "25111",
        constant = "A069",
        model = "Phone (4a)",
        channels = 6,
        strips = listOf(Strip("A", "A", 0, 6, StripShape.Line(0.68f, 0.16f, 0.68f, 0.84f))),
    )

    /** Phone (4b): 4 Quadrate in einer senkrechten Reihe. */
    val PHONE_4B = DeviceSpec(
        key = "25131",
        constant = "A009P",
        model = "Phone (4b)",
        channels = 4,
        strips = listOf(Strip("A", "A", 0, 4, StripShape.Line(0.72f, 0.20f, 0.72f, 0.80f))),
    )

    /** Alle Geraete mit Segment-Glyphen, in der Reihenfolge ihres Erscheinens. */
    val segment: List<DeviceSpec> = listOf(
        PHONE_1,
        PHONE_2,
        PHONE_2A,
        PHONE_2A_PLUS,
        PHONE_3A,
        PHONE_4A,
        PHONE_4B,
    )

    /** Matrix-Geraete: erkannt, aber nicht ueber Kanalzahlen steuerbar. */
    private val matrix: List<Pair<String, String>> = listOf(
        "A024" to "Phone (3)",
        "A069P" to "Phone (4a) Pro",
    )

    fun byKey(key: String): DeviceSpec? = segment.firstOrNull { it.key == key }

    /**
     * Das Geraet zu einer Modellkennung.
     *
     * Das GDK vergleicht `Build.MODEL` mit Gleichheit gegen seine Konstante.
     * Ein Praefix-Fall kommt dazu, weil dieselbe Baureihe je Region eine
     * Endung traegt (`A059P`); exakte Treffer haben Vorrang, damit `A142P`
     * nicht beim `A142` landet. Ein Matrix-Geraet bekommt hier nie eine
     * Segment-Tabelle -- `A069P` ist das (4a) Pro mit Pixelgitter und faengt
     * sonst beim Praefix des (4a) ab.
     */
    fun forModel(model: String): DeviceSpec? {
        if (matrixName(model) != null) return null
        segment.firstOrNull { it.constant == model || model in it.aliases }?.let { return it }
        return segment.firstOrNull { model.startsWith(it.constant) }
    }

    /** Der Anzeigename eines Matrix-Geraets, sonst `null`. */
    fun matrixName(model: String): String? =
        matrix.firstOrNull { it.first == model }?.second

    private fun point(x: Float, y: Float) = StripShape.Line(x, y, x, y)
}
