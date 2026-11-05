package com.steimerbyte.glyphorchestra.ui

import android.content.Context
import android.net.Uri
import com.steimerbyte.glyphorchestra.audio.Analyzer
import com.steimerbyte.glyphorchestra.audio.AnalysisResult
import com.steimerbyte.glyphorchestra.audio.Dsp
import com.steimerbyte.glyphorchestra.audio.MediaAudioSource
import com.steimerbyte.glyphorchestra.audio.PcmSource
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import java.io.IOException

/**
 * Die eine Stelle, an der die Oberflaeche eine Datei analysiert.
 *
 * Zwei Gruende fuer den Umweg ueber ein Objekt:
 * - Der Test ersetzt [run] durch eine feste Antwort; MediaExtractor und
 *   MediaCodec gibt es auf der JVM nicht. Dieselbe Bauform wie
 *   [com.steimerbyte.glyphorchestra.glyph.GlyphProvider].
 * - Der Fortschritt wird hier auf einen einzigen Bruchteil 0..1 gebracht. Der
 *   [Analyzer] meldet je Runde eigene Zahlen, die erste ohne Gesamtzahl; erst
 *   mit der beim Waehlen gemessenen Dauer laesst sich dort ein Prozentwert
 *   zeigen.
 */
object AnalysisRunner {

    /**
     * [onProgress] bekommt 0..1, [aborted] wird zwischen zwei Leseblöcken
     * gefragt -- so endet eine ueberholte Analyse wirklich, statt im
     * Hintergrund weiterzurechnen.
     */
    var run: (
        Context,
        Uri,
        DeviceSpec,
        Double,
        (Double) -> Unit,
        () -> Boolean,
    ) -> AnalysisResult = ::analyze

    private fun analyze(
        context: Context,
        uri: Uri,
        spec: DeviceSpec,
        durationSec: Double,
        onProgress: (Double) -> Unit,
        aborted: () -> Boolean,
    ): AnalysisResult = MediaAudioSource.open(context, uri).use { source ->
        // Geschaetzte Analyseframes aus der Dateidauer: die erste Runde zaehlt
        // nur mit und kennt ihr Ende nicht.
        val estimate = durationSec * Dsp.analysisFps(source.sampleRate)
        Analyzer.analyze(Abortable(source, aborted), spec) { progress ->
            onProgress(share(progress, estimate))
        }
    }

    /**
     * Eine Zahl fuer drei Runden: Kennzahlen sammeln 0..50 %, Tempo 50 %,
     * Bilder zeichnen 50..95 %, Fertig 100 %.
     */
    private fun share(progress: Analyzer.Progress, estimate: Double): Double = when (progress.stage) {
        "Tempo" -> 0.5
        "Zeichne" -> if (progress.total > 0) 0.5 + 0.45 * progress.done / progress.total else 0.5
        "Fertig" -> 1.0
        else -> if (estimate > 0) (0.5 * progress.done / estimate).coerceIn(0.0, 0.5) else 0.0
    }

    private class Abortable(
        private val inner: PcmSource,
        private val aborted: () -> Boolean,
    ) : PcmSource {

        override val sampleRate: Int get() = inner.sampleRate

        // shortcut: greift zwischen zwei Lesebloecken; die zweite Runde des
        // Analyzers rechnet aus dem Speicher und laeuft zu Ende (Sekundenbruchteile).
        override fun read(dst: FloatArray): Int {
            if (aborted()) throw IOException("Analyse abgebrochen")
            return inner.read(dst)
        }
    }
}
