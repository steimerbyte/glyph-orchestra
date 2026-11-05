package com.steimerbyte.glyphorchestra.glyph

/**
 * Der Pegel: eine Zahl von 0 bis 100 Prozent, die fuer jeden Kanal gilt.
 *
 * Das SDK rechnet in rohen Werten je Kanal: `GlyphFrame.DEFAULT_LIGHT` ist
 * 4000, die eigene Atemanimation laeuft bis 4096. Mehr als 4000 ist also nicht
 * belegt -- deshalb ist [MAX] genau der SDK-Vorgabewert und nicht die
 * Obergrenze. Zwischen 0 und 4000 gibt es im SDK keinen Sprung, also wird
 * linear abgebildet und auf ganze Werte gerundet: 1 Prozent sind 40, 30
 * Prozent 1200, 50 Prozent 2000. Wie fein die LED den Rohwert wirklich
 * aufloest, steht in keiner Unterlage -- deshalb nennt die Anzeige Prozent und
 * den Wert, aber keine Stufen, die niemand nachmessen kann.
 *
 * 0 heisst dunkel. Ein Rohwert von 0 ist im SDK eine dunkle LED, kein
 * gedimmter; die Auswahl bleibt dabei gewaehlt (siehe [Selection]), damit ein
 * hoeherer Wert anschliessend wieder etwas einschaltet.
 */
object Level {

    /** Obergrenze in Prozent. */
    const val MAX_PERCENT = 100

    /** Vollwert des SDK: `GlyphFrame.DEFAULT_LIGHT`. */
    const val MAX = 4000

    /**
     * Die drei Kurzwahlen der Oberflaeche -- sie setzen denselben Regler, sind
     * also keine zweite Quelle fuer die Helligkeit.
     */
    val PERCENTS = intArrayOf(30, 65, MAX_PERCENT)

    /** Rohwert zu einem Prozentwert. Werte ausserhalb 0..100 werden geklemmt. */
    fun value(percent: Int): Int {
        val clamped = clamp(percent)
        return (clamped * MAX + MAX_PERCENT / 2) / MAX_PERCENT
    }

    fun clamp(percent: Int): Int = percent.coerceIn(0, MAX_PERCENT)
}
