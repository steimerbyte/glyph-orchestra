package com.steimerbyte.glyphorchestra.model

import com.steimerbyte.glyphorchestra.device.Channel
import com.steimerbyte.glyphorchestra.device.DeviceSpec

/**
 * Ein benannter Satz Kanaele, gespeichert als Codes (`A7`, `C1_4`).
 *
 * Codes statt Indizes, weil ein Preset lesbar bleiben soll: eine Datei voller
 * Zahlen sagt niemandem, welche LED gemeint war. Welcher Code auf welchem
 * Geraet existiert, entscheidet [DeviceSpec] -- ein Preset vom Phone (3a) hat
 * auf dem Phone (4a) schlicht weniger Kanaele.
 */
data class Preset(val name: String, val codes: List<String>) {

    fun channels(spec: DeviceSpec): List<Channel> = spec.channelsOf(codes)

    fun size(spec: DeviceSpec): Int = channels(spec).size
}

/**
 * Textformat der Presets.
 *
 * Warum kein JSON: `org.json` ist in einfachen JVM-Tests nicht vorhanden (es
 * ist ein Android-Stub), das Format waere also nur mit Robolectric pruefbar.
 * Eine Zeile je Preset, Name und Codes durch Tabulator getrennt, ist genauso
 * lesbar, in jedem Editor zu reparieren -- und der Codec laesst sich ohne
 * Android testen. Namen duerfen keinen Tabulator enthalten; das erzwingt
 * [sanitizeName] beim Speichern.
 */
object PresetCodec {

    const val HEADER = "#glyphdeck-presets-1"
    const val MAX_NAME = 24

    /** Trimmt, zieht Leerraum zusammen, entfernt Tabulatoren, begrenzt die Laenge. */
    fun sanitizeName(raw: String): String = raw
        .replace('\t', ' ')
        .replace('\n', ' ')
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(MAX_NAME)

    fun encode(presets: List<Preset>, spec: DeviceSpec): String = buildString {
        append(HEADER)
        for (preset in presets) {
            val codes = preset.channels(spec).map { it.code }.distinct()
            if (preset.name.isEmpty() || codes.isEmpty()) continue
            append('\n')
            append(preset.name)
            append('\t')
            append(codes.joinToString(","))
        }
    }

    fun decode(text: String?, spec: DeviceSpec): List<Preset> {
        if (text.isNullOrBlank()) return emptyList()
        // Schluessel ist der Name in Kleinschreibung: "Rot" und "rot" sind
        // derselbe Eintrag. Genau so vergleicht [PresetStore.save], sonst
        // haette die Datei zwei Eintraege, das Speichern aber nur einen.
        val result = LinkedHashMap<String, Preset>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = sanitizeName(line.substring(0, tab))
            if (name.isEmpty()) continue
            val codes = line.substring(tab + 1)
                .split(',')
                .map { it.trim() }
                .filter { spec.of(it) != null }
                .distinct()
            if (codes.isEmpty()) continue
            // Gleicher Name ueberschreibt: sonst haette ein zweiter Eintrag
            // keine Wirkung, weil die Liste nach Namen sucht.
            val key = name.lowercase()
            result.remove(key)
            result[key] = Preset(name, codes)
        }
        return result.values.toList()
    }
}
