package com.steimerbyte.glyphorchestra.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import com.steimerbyte.glyphorchestra.R
import com.steimerbyte.glyphorchestra.audio.AnalysisResult

/**
 * Die 17 Baender ueber die Zeit als Bild, darueber die Beat-Zeiten und der
 * Zeiger des Abspielers.
 *
 * Warum ein Bitmap statt Rechtecken: 17 Baender auf zehntausend Spalten waeren
 * je Bild 170 000 drawRect-Aufrufe. Das Raster entsteht einmal je Analyse, im
 * [onDraw] liegen nur noch vier Zeichenaufrufe. Paints, Zielrechteck und
 * Beat-Pfad sind Felder -- waehrend des Abspielens (30 Bilder/s) wird nichts
 * angelegt.
 */
class BandChartView(context: Context) : View(context) {

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = context.getColor(R.color.nothing_line)
    }

    /** Beats duenn und zurueckhaltend: sie sind Raster, nicht Inhalt. */
    private val beatPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = context.getColor(R.color.nothing_grey)
        alpha = 0x66
    }

    private val headPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = context.getColor(R.color.nothing_white)
    }

    private val target = Rect()
    private val beatPath = Path()

    private var bitmap: Bitmap? = null
    private var beats: DoubleArray = DoubleArray(0)
    private var durationSec = 0.0
    private var beatsDirty = false
    private var playhead = -1f

    private val inset = (resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)

    /**
     * Setzt das Raster; [AnalysisResult.bandsDb] ist bereits zeilenmajor
     * (Frame * Bänder + Band). Der Aufruf rechnet auf dem Main-Thread einmal
     * ueber alle Bildpunkte -- das ist der Preis dafuer, dass [onDraw] nichts
     * mehr tun muss.
     */
    fun setAnalysis(result: AnalysisResult) {
        val frames = result.frames
        val bands = if (frames > 0) result.bandsDb.size / frames else 0
        durationSec = result.durationSec
        beats = result.beats
        beatsDirty = true
        playhead = -1f

        if (frames <= 0 || bands <= 0 || result.bandsDb.size < frames * bands) {
            bitmap = null
            invalidate()
            return
        }

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (value in result.bandsDb) {
            if (value < min) min = value
            if (value > max) max = value
        }
        val span = if (max - min > 0.01f) max - min else 1f

        val pixels = IntArray(frames * bands)
        for (band in 0 until bands) {
            // Band 0 (der Kick) liegt unten: ein Spektrogramm liest von tief nach hoch.
            val row = (bands - 1 - band) * frames
            for (frame in 0 until frames) {
                val t = ((result.bandsDb[frame * bands + band] - min) / span).coerceIn(0f, 1f)
                pixels[row + frame] = shade(t)
            }
        }
        val raster = Bitmap.createBitmap(frames, bands, Bitmap.Config.ARGB_8888)
        raster.setPixels(pixels, 0, frames, 0, 0, frames, bands)
        bitmap = raster
        invalidate()
    }

    /** [fraction] 0..1, negativ blendet den Zeiger aus. */
    fun setPlayhead(fraction: Float) {
        playhead = if (fraction < 0f) -1f else fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        bitmap = null
        beats = DoubleArray(0)
        beatsDirty = false
        playhead = -1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        beatsDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val left = inset
        val top = inset
        val right = (width - inset).coerceAtLeast(left)
        val bottom = (height - inset).coerceAtLeast(top)

        bitmap?.let { raster ->
            if (beatsDirty) buildBeats(left, top, right, bottom)
            target.set(left, top, right, bottom)
            canvas.drawBitmap(raster, null, target, imagePaint)
            if (beats.isNotEmpty()) canvas.drawPath(beatPath, beatPaint)
            if (playhead >= 0f) {
                val x = left + (right - left) * playhead
                canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), headPaint)
            }
        }
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), borderPaint)
    }

    /** Pfad in Pixeln; laeuft nur nach neuer Analyse oder Groessenaenderung. */
    private fun buildBeats(left: Int, top: Int, right: Int, bottom: Int) {
        beatsDirty = false
        beatPath.rewind()
        if (durationSec <= 0.0) return
        val width = (right - left).toFloat()
        for (beat in beats) {
            val x = left + width * (beat / durationSec).toFloat()
            if (x < left || x > right) continue
            beatPath.moveTo(x, top.toFloat())
            beatPath.lineTo(x, bottom.toFloat())
        }
    }

    /** Nothing-Rot, von 10 % bis 100 % Helligkeit; Schwarz bleibt der Grund. */
    private fun shade(t: Float): Int {
        val k = 0.10f + 0.90f * t
        return Color.rgb((0xD7 * k).toInt(), (0x19 * k).toInt(), (0x21 * k).toInt())
    }
}
