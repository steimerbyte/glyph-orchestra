package com.steimerbyte.glyphorchestra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/**
 * Synthetische Signale für die Onset- und Tempotests.
 *
 * Ein Klick ist ein breitbandiger Impuls; im 46-ms-Hann-Fenster des Frontends
 * steht er in vier aufeinanderfolgenden Frames mit dem Fenstergewicht im
 * Quadrat. Genau diese Ausbreitung erzeuge ich als Leistungsspektrum, damit
 * kein Audio-Decoder und kein Gerät nötig ist.
 */
internal object ClickTrack {
    const val SAMPLE_RATE = 44100.0
    const val FPS = SAMPLE_RATE / Dsp.HOP
    private const val BINS = 513
    private const val FIRST_SEC = 0.3

    /**
     * Klickzug ab [FIRST_SEC].
     *
     * [accentEvery] > 0 macht daraus einen Walzer: der betonte Klick liegt
     * nur im Tiefton (Bins 1..15 = Kick/Bass), die anderen nur im Hochton
     * (Becken/Snare). Ein reiner Lautstärkeunterschied taugt nicht als
     * Akzent -- die Log-Kompression mit lambda = 1000 macht aus 1,0 gegen
     * 0,45 nur wenige Prozent Flussdifferenz, genau wie gewollt. Die Taktart
     * steckt in der Besetzung, nicht im Pegel (Bericht §4).
     */
    fun track(
        bpm: Double,
        seconds: Double,
        accentEvery: Int = 0,
    ): List<FloatArray> {
        val frames = MutableList(ceil(seconds * FPS).toInt() + 1) { FloatArray(BINS) }
        val step = 60.0 / bpm
        var index = 0
        while (true) {
            val time = FIRST_SEC + index * step
            if (time >= seconds) break
            val bins = when {
                accentEvery == 0 -> 1..512
                index % accentEvery == 0 -> 1..15
                else -> 16..512
            }
            place(frames, time, bins)
            index++
        }
        return frames
    }

    /** Linear beschleunigender Klickzug; trainiert den Zwei-Pass des DP. */
    fun ramp(startBpm: Double, stepPerClick: Double, seconds: Double): List<FloatArray> {
        val frames = MutableList(ceil(seconds * FPS).toInt() + 1) { FloatArray(BINS) }
        var time = FIRST_SEC
        var bpm = startBpm
        while (time < seconds) {
            place(frames, time, 1..512)
            time += 60.0 / bpm
            bpm += stepPerClick
        }
        return frames
    }

    private fun place(frames: MutableList<FloatArray>, time: Double, bins: IntRange) {
        val sample = time * SAMPLE_RATE
        val first = floor((sample - Dsp.N) / Dsp.HOP).toInt()
        val last = floor(sample / Dsp.HOP).toInt()
        for (i in first..last) {
            if (i !in frames.indices) continue
            val position = (sample - i * Dsp.HOP) / Dsp.N
            if (position < 0.0 || position >= 1.0) continue
            val power = hann(position).pow(2).toFloat()
            val row = frames[i]
            for (bin in bins) row[bin] += power
        }
    }

    /** Konstantes Rauschen: jedes Bin jedes Frames unabhängig, fester Seed. */
    fun noise(seconds: Double, seed: Long): List<FloatArray> {
        val random = java.util.Random(seed)
        return List(ceil(seconds * FPS).toInt() + 1) {
            FloatArray(BINS) { bin ->
                if (bin in 1..512) (0.1 + 0.9 * random.nextDouble()).toFloat() else 0f
            }
        }
    }

    fun silence(seconds: Double): List<FloatArray> =
        List(ceil(seconds * FPS).toInt() + 1) { FloatArray(BINS) }

    fun analyse(frames: List<FloatArray>): OnsetEnvelope {
        val detector = OnsetDetector(FPS)
        for (frame in frames) detector.addFrame(frame)
        return detector.finish()
    }

    fun hann(x: Double): Double = 0.5 - 0.5 * cos(2.0 * PI * x)
}

class OnsetTest {

    @Test
    fun `ein klickzug gibt einen peak je klick`() {
        val envelope = ClickTrack.analyse(ClickTrack.track(120.0, 8.0))

        val peaks = (1 until envelope.size - 1).filter { i ->
            envelope.dp[i] > 0.5f && envelope.dp[i] >= envelope.dp[i - 1] && envelope.dp[i] >= envelope.dp[i + 1]
        }
        // 8 s bei 120 BPM sind 16 Klicks. Ein Klick liefert wegen der
        // Fensterausbreitung bis zu zwei Flussframes; nach der
        // Halbwellengleichrichtung bleibt ein Scheitel übrig.
        assertEquals("Peaks: $peaks", 16, peaks.size)
        for (i in 1 until peaks.size) {
            val spacing = peaks[i] - peaks[i - 1]
            assertTrue("Abstand $spacing bei Peak $i", spacing in 41..46)
        }
    }

    @Test
    fun `dp ist nicht negativ und der acf kanal ist nullmittig`() {
        val envelope = ClickTrack.analyse(ClickTrack.track(120.0, 4.0))

        assertTrue("negativer DP-Wert", envelope.dp.min() >= 0f)
        assertEquals(1.0, std(envelope.dp), 1e-4)
        assertTrue("DP-Mittelwert ist nicht positiv", envelope.dp.average() > 0.0)

        assertEquals(0.0, envelope.acf.average(), 1e-4)
        assertEquals(1.0, std(envelope.acf), 1e-4)
    }

    @Test
    fun `stille ergibt keine hüllkurve`() {
        val envelope = ClickTrack.analyse(ClickTrack.silence(3.0))

        assertTrue(envelope.dp.all { it == 0f })
        assertTrue(envelope.acf.all { it == 0f })
        assertTrue(envelope.low.all { it == 0f })
    }

    @Test
    fun `ein konstantes spektrum ohne transienten ergibt keine onsets`() {
        val flat = List(300) { FloatArray(513) { bin -> if (bin in 1..512) 0.25f else 0f } }
        val envelope = ClickTrack.analyse(flat)

        // Konstanter Pegel ist kein Onset; ohne die lokale Mittelwert-
        // entfernung wäre hier trotzdem eine flache Hüllkurve.
        assertTrue(envelope.dp.all { it == 0f })
    }

    @Test
    fun `die framezeit liegt in der fenstermitte`() {
        // 86,1328 fps: Frame 0 ist bei 2/86,1328 = 23,2 ms, nicht bei 0.
        assertEquals(2.0 / ClickTrack.FPS, frameCenterSec(0.0, ClickTrack.FPS), 1e-12)
        assertEquals(45.0 / ClickTrack.FPS, frameCenterSec(43.0, ClickTrack.FPS), 1e-12)
        val envelope = OnsetEnvelope(ClickTrack.FPS, FloatArray(1), FloatArray(1), FloatArray(1))
        assertEquals(frameCenterSec(7.0, ClickTrack.FPS), envelope.timeSec(7), 1e-12)
    }

    private fun std(values: FloatArray): Double {
        val mean = values.average()
        var squares = 0.0
        for (v in values) {
            val d = v - mean
            squares += d * d
        }
        return kotlin.math.sqrt(squares / values.size)
    }
}
