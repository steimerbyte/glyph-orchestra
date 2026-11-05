package com.steimerbyte.glyphorchestra.model

import android.content.Context
import android.content.SharedPreferences

/**
 * Was ausser Presets in den Einstellungen steht.
 *
 * Ein eigener Typ und ein eigener Schluessel je Wert: verstreute
 * `getString`/`putInt`-Aufrufe an mehreren Stellen sind die Ursache dafuer,
 * dass zwei Stellen denselben Wert verschieden lesen.
 *
 * Der Name der Datei steht hier und wird von [PresetStore] mitbenutzt -- zwei
 * Konstanten mit demselben Wert waeren genau die doppelte Quelle, die dieser
 * Typ verhindern soll.
 */
data class Settings(val sound: Boolean = true) {

    fun save(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SOUND, sound)
            .apply()
    }

    companion object {
        /** Name der Datei, die alle Einstellungen der App haelt. */
        const val PREFS = "glyphdeck"

        private const val KEY_SOUND = "sound"

        /**
         * Töne sind ab Werk an: eine App, die beim Schalten nichts hoert,
         * wirkt kaputt. Wer sie nicht will, schaltet sie ab.
         */
        fun load(prefs: SharedPreferences): Settings =
            Settings(sound = prefs.getBoolean(KEY_SOUND, true))
    }
}
