package com.steimerbyte.glyphorchestra.audio

/**
 * Mono-PCM als Float, -1..1.
 *
 * Warum ein eigenes Interface: WAV und MP3/AAC/Ogg kommen ueber verschiedene
 * Wege herein, die Analyse soll aber nur FloatArrays sehen und ohne Android
 * testbar bleiben.
 */
interface PcmSource {
    val sampleRate: Int

    /** Liest bis zu dst.size Werte; Rueckgabe < 0 heisst Ende. */
    fun read(dst: FloatArray): Int
}

/** Gemeinsame Zahlen der Analyse. */
object Dsp {
    /** Fensterlaenge in Samples (Hann, 46,4 ms bei 44,1 kHz). */
    const val N = 2048

    /** Hop in Samples; N/4. */
    const val HOP = 512

    /** Anzahl der logarithmischen Baender (100 Hz .. 11 kHz). */
    const val BANDS = 16

    /** Zusaetzliches Tieftonband fuer den Kick-Grundton. */
    const val LOW_BAND_HZ_FROM = 35.0
    const val LOW_BAND_HZ_TO = 110.0

    /** Anzeige-Bildrate der Glyph-Sequenz. */
    const val FPS_DRAW = 30

    /** Analyse-Bildrate bei gegebener Abtastrate. */
    fun analysisFps(sampleRate: Int): Double = sampleRate.toDouble() / HOP

    /** Frequenzaufloesung bei gegebener Abtastrate. */
    fun binHz(sampleRate: Int): Double = sampleRate.toDouble() / N
}

/** Ergebnis einer kompletten Analyse, alles was der Player braucht. */
data class AnalysisResult(
    /** Anzeige-Bildrate, mit der [levels] indiziert ist. */
    val fpsDraw: Int,
    /** Anzahl Anzeigeframes. */
    val frames: Int,
    /** frames * 36 Helligkeiten, Zeilenmajor: levels[f * 36 + channel.index]. */
    val levels: IntArray,
    /** Spannung je Anzeigeframe, 0..1. */
    val tension: FloatArray,
    /** Baender in dB je Analyseframe, Zeilenmajor: bandsDb[f * 17 + b]. */
    val bandsDb: FloatArray,
    /** Analyse-Bildrate, mit der bandsDb indiziert ist. */
    val fpsAnalysis: Double,
    val bpm: Double,
    /** Beat-Zeiten in Sekunden, aufsteigend. */
    val beats: DoubleArray,
    /** "4/4", "3/4" oder "?" */
    val meter: String,
    /** Konfidenz der Temp Schaetzung, 0..1. */
    val confidence: Double,
    val durationSec: Double,
) {
    val channels: Int get() = 36
}
