package com.steimerbyte.glyphorchestra.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.steimerbyte.glyphorchestra.audio.AnalysisResult
import com.steimerbyte.glyphorchestra.audio.Dsp
import com.steimerbyte.glyphorchestra.glyph.GlyphOutput

/**
 * Spielt eine fertig gerechnete Sequenz auf den Glyphen ab.
 *
 * Warum die Bildnummer aus der Uhr kommt und nicht hochgezählt wird: ein
 * Handler-Takt schwankt um einige Millisekunden, und ein hochgezählter Zähler
 * läuft dann über drei Minuten um mehrere Bilder aus dem Takt. Aus der
 * verstrichenen Zeit gerechnet holt der Abspieler Rückstand von selbst auf.
 */
class GlyphOrchesterPlayer(private val output: GlyphOutput) {

    private val handler = Handler(Looper.getMainLooper())
    private var result: AnalysisResult? = null
    private var startedAt = 0L
    private var lastFrame = -1

    /** Wird je geschriebenem Bild gerufen, auf dem Main-Thread. */
    var onFrame: ((frame: Int, total: Int) -> Unit)? = null

    /** Wird am Ende und beim Abbruch gerufen. */
    var onFinished: (() -> Unit)? = null

    val isPlaying: Boolean get() = result != null

    private val tick = object : Runnable {
        override fun run() {
            val r = result ?: return
            val elapsedMs = SystemClock.uptimeMillis() - startedAt
            val frame = ((elapsedMs * Dsp.FPS_DRAW) / 1000L).toInt()
            if (frame >= r.frames) {
                stop()
                onFinished?.invoke()
                return
            }
            if (frame != lastFrame) {
                lastFrame = frame
                output.apply(r.levels.copyOfRange(frame * 36, frame * 36 + 36))
                onFrame?.invoke(frame, r.frames)
            }
            // Auf die naechste Bildgrenze legen, nicht auf jetzt+33 ms,
            // sonst summiert sich die Taktungenauigkeit ueber den Song.
            val nextAt = startedAt + ((frame + 1) * 1000L) / Dsp.FPS_DRAW
            handler.postAtTime(this, nextAt)
        }
    }

    fun play(r: AnalysisResult) {
        stop()
        result = r
        lastFrame = -1
        startedAt = SystemClock.uptimeMillis()
        handler.postAtTime(tick, startedAt)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        val wasPlaying = result != null
        result = null
        lastFrame = -1
        if (wasPlaying) output.off()
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }
}
