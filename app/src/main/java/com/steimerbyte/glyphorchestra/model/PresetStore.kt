package com.steimerbyte.glyphorchestra.model

import android.content.Context
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.glyph.Level
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Was beim Start aus den Einstellungen kommt. */
data class Library(
    val presets: List<Preset>,
    val last: Preset?,
    /** Der zuletzt eingestellte Pegel in Prozent. */
    val percent: Int,
    val settings: Settings = Settings(),
)

/**
 * Presets und der zuletzt eingestellte Rahmen, abgelegt in SharedPreferences.
 *
 * Die Schluessel tragen den Geraeteschluessel (`presets_24111`): ein Preset
 * nennt Kanaele, und die gibt es nur auf einem Geraet in dieser Form. Fehlt
 * der Schluessel, wird der alte, geraetelose gelesen -- so ueberlebt die
 * Auswahl das Update, und der naechste Speichervorgang schreibt sie unter dem
 * neuen Namen.
 *
 * Gelesen wird in einem eigenen Thread: die Datei liegt zwar hinter
 * SharedPreferences im Speicher, der erste Zugriff liest sie aber von der
 * Platte -- und das gehoert nicht auf den Haupt-Thread, auch wenn es "nur
 * wenige Kilobyte" sind. Geschrieben wird mit `apply()` (asynchron).
 */
class PresetStore(context: Context, private val spec: DeviceSpec) {

    private val prefs = context.applicationContext
        .getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "preset-io").apply { isDaemon = true }
    }

    /**
     * Die mitgelieferten Beispiele: nicht loeschbar, immer sichtbar.
     *
     * Sie sind aus dem Geraet gerechnet und nicht festgeschrieben, damit sie
     * auf jeder Kanalzahl etwas zeigen: alles, jeder zweite, die untere
     * Haelfte.
     */
    val builtIns: List<Preset> = listOf(
        Preset("RING", spec.all.map { it.code }),
        Preset("WECHSEL", spec.all.filterIndexed { index, _ -> index % 2 == 0 }.map { it.code }),
        Preset("HAELFTE", spec.all.drop(spec.channels / 2).map { it.code }),
    ).filter { it.codes.isNotEmpty() }

    fun loadAsync(onDone: (Library) -> Unit) {
        io.execute {
            val presets = PresetCodec.decode(read(KEY_PRESETS), spec)
            val lastCodes = read(KEY_LAST)
                ?.split(',')
                ?.filter { spec.of(it) != null }
                .orEmpty()
            // Der alte Schluessel trug dieselbe Skala (30, 65, 100 Prozent);
            // wer von einer aelteren Fassung kommt, behaelt seinen Pegel.
            val percent = Level.clamp(
                prefs.getInt(key(KEY_PERCENT), prefs.getInt(key(KEY_LEVEL), Level.MAX_PERCENT))
            )
            val library = Library(
                presets = presets,
                last = if (lastCodes.isEmpty()) null else Preset(LAST_NAME, lastCodes),
                percent = percent,
                // Im selben Thread wie der Rest: der erste Zugriff auf diese
                // Datei liest sie von der Platte, und das gehoert nicht auf
                // den Haupt-Thread.
                settings = Settings.load(prefs),
            )
            onDone(library)
        }
    }

    /** Was das Speichern verhindert hat. Der Text dazu steht in den Ressourcen. */
    enum class SaveError { EMPTY_NAME, EMPTY_SELECTION, FULL }

    /** Speichert oder meldet, warum nicht. `null` heisst: gespeichert. */
    fun save(preset: Preset): SaveError? {
        val name = PresetCodec.sanitizeName(preset.name)
        if (name.isEmpty()) return SaveError.EMPTY_NAME
        val codes = preset.channels(spec).map { it.code }.distinct()
        if (codes.isEmpty()) return SaveError.EMPTY_SELECTION

        val current = PresetCodec.decode(read(KEY_PRESETS), spec).toMutableList()
        val existing = current.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (existing >= 0) {
            current[existing] = Preset(name, codes)
        } else {
            if (current.size >= MAX_PRESETS) return SaveError.FULL
            current += Preset(name, codes)
        }
        prefs.edit().putString(key(KEY_PRESETS), PresetCodec.encode(current, spec)).apply()
        return null
    }

    fun delete(name: String) {
        val current = PresetCodec.decode(read(KEY_PRESETS), spec)
            .filterNot { it.name == name }
        prefs.edit().putString(key(KEY_PRESETS), PresetCodec.encode(current, spec)).apply()
    }

    fun remember(codes: List<String>, percent: Int) {
        prefs.edit()
            .putString(key(KEY_LAST), codes.joinToString(","))
            .putInt(key(KEY_PERCENT), Level.clamp(percent))
            .apply()
    }

    fun shutdown() {
        io.shutdown()
    }

    /** Der Wert unter dem Geraeteschluessel, sonst der alte geraetelose. */
    private fun read(name: String): String? =
        prefs.getString(key(name), null) ?: prefs.getString(name, null)

    private fun key(name: String): String = "${name}_${spec.key}"

    companion object {
        const val LAST_NAME = "ZULETZT"
        const val MAX_PRESETS = 20

        private const val KEY_PRESETS = "presets"
        private const val KEY_LAST = "last"
        private const val KEY_PERCENT = "percent"

        /** Vor der Umbenennung: derselbe Wert, dieselbe Skala in Prozent. */
        private const val KEY_LEVEL = "level"
    }
}
