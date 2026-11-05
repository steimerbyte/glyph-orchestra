package com.steimerbyte.glyphorchestra.audio

import com.steimerbyte.glyphorchestra.device.DeviceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt die Mono-Annahme des Analysepfads fest.
 *
 * [PcmSource] hat bewusst keinen Kanalparameter: die ganze Kette rechnet mit
 * einem Float-Strom. Die Kanalzahl wird ausschliesslich beim Dekodieren
 * behandelt, und zwar in `MediaAudioSource`:
 *
 * - `MediaAudioSource.kt:190` liest `KEY_CHANNEL_COUNT` aus dem Ausgabeformat
 *   des Decoders, nicht aus dem Track-Header (HE-AAC/Parametric Stereo).
 * - `MediaAudioSource.kt:230` rechnet `frameBytes = sampleBytes * channels`.
 * - `MediaAudioSource.kt:244-264` summiert die Sprecher (LFE ausgelassen) und
 *   teilt durch `voices`, also Mittelwert-Downmix. Danach sieht kein Modul
 *   mehr eine Kanalzahl.
 *
 * Ohne Geraet ist der Downmix selbst nicht ausfuehrbar; der Test sichert die
 * Schnittstelle, die ihn ersetzt: eine Quelle kann nur mono liefern, und der
 * Analyzer schreibt immer 36 Kanaele je Frame.
 */
class PcmContractTest {

    @Test
    fun `pcm quelle kennt nur rate und einen mono strom`() {
        assertTrue("PcmSource ist keine Schnittstelle", PcmSource::class.java.isInterface)
        val methods = PcmSource::class.java.declaredMethods.map { it.name }.sorted()
        assertEquals("PcmSource kann eine Kanalzahl ausdruecken", listOf("getSampleRate", "read"), methods)
    }

    @Test
    fun `analyzer schreibt genau 36 kanaele je frame`() {
        val source = object : PcmSource {
            override val sampleRate = 44100
            private var given = 0
            override fun read(dst: FloatArray): Int {
                if (given >= 20) return -1
                dst.fill(0f)
                given++
                return dst.size
            }
        }
        val r = Analyzer.analyze(source, DeviceCatalog.PHONE_3A)

        assertEquals(36, r.channels)
        assertEquals(r.frames * 36, r.levels.size)
        assertEquals(0.0, r.bpm, 0.0)
    }
}
