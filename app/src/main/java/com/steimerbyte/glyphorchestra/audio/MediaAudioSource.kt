package com.steimerbyte.glyphorchestra.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Eine Audiodatei als Mono-Float-Strom, -1..1.
 *
 * Warum Extractor + Codec und nicht MediaPlayer/Media3: MediaPlayer liefert nur
 * einen Visualizer während echter Wiedergabe, Media3 bringt 1,5 MB und einen
 * eigenen Threading-Stack für null Gewinn (Bericht §1.1).
 *
 * Zwei harte Regeln aus dem Bericht, die hier den Aufbau bestimmen:
 * - Abtastrate, Kanalzahl und PCM-Encoding kommen ausschließlich aus
 *   [MediaCodec.getOutputFormat], nie aus dem Track-Header. HE-AAC verdoppelt
 *   die Rate, Parametric Stereo die Kanalzahl, manche Decoder liefern Float.
 *   Deshalb dekodiert [open] schon den ersten Puffer, bevor es zurückkehrt:
 *   [sampleRate] ist ab dann die Wahrheit.
 * - Der Dateideskriptor bleibt bis [close] offen. Ein `use { }` um
 *   [MediaExtractor.setDataSource] schließt ihn zu früh, und der Extractor
 *   liest danach Müll.
 *
 * Downmix ist der Mittelwert über die Kanäle (Bericht §1.4): bei korrelierten
 * Kanälen übersteuert (L+R)/√2 um 3 dB, der Mittelwert erhält 0 dBFS als
 * Referenz und fällt in allen logarithmischen Folgestufen als Konstante heraus.
 *
 * Testbarkeit: MediaExtractor und MediaCodec sind Geräte-APIs, ein JVM-Test
 * kann nur Attrappen nachstellen und würde nichts über echtes Decoding
 * aussagen. Geprüft ist hier deshalb ausschließlich die Übersetzung; die
 * Rechenpfade (Fft, Spectrum) sind durch FftTest und SpectrumTest abgedeckt,
 * der Rest braucht ein Telefon mit einer echten Datei.
 */
class MediaAudioSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec?,
    private val fd: ParcelFileDescriptor,
) : PcmSource, Closeable {

    /** Abtastrate des Decoders; gültig, sobald [open] zurückkehrt. */
    override var sampleRate: Int = 0
        private set

    private var channels = 1
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    /** Kanalposition des LFE in der Interleaving-Reihenfolge, -1 wenn keine. */
    private var lfeSlot = -1

    private val info = MediaCodec.BufferInfo()

    /** Nur für WAV (audio/raw): dort liest der Extractor die Samples selbst. */
    private val rawChunk: ByteBuffer? =
        if (codec == null) ByteBuffer.allocate(RAW_CHUNK) else null

    /** Mono-Samples des zuletzt geholten Chunks, noch nicht an den Aufrufer abgegeben. */
    private var pending = FloatArray(PENDING_START)
    private var pendingPos = 0
    private var pendingCount = 0

    private var inputDone = false
    private var formatDirty = true
    private var eos = false

    override fun read(dst: FloatArray): Int {
        if (dst.isEmpty()) return 0
        var filled = 0
        while (filled < dst.size) {
            if (pendingPos >= pendingCount) {
                pendingPos = 0
                pendingCount = 0
                if (!pull()) break
            }
            val n = minOf(pendingCount - pendingPos, dst.size - filled)
            System.arraycopy(pending, pendingPos, dst, filled, n)
            pendingPos += n
            filled += n
        }
        return if (filled > 0) filled else -1
    }

    override fun close() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        runCatching { extractor.release() }
        // Zuletzt: erst wenn der Extractor fertig ist, darf der Deskriptor weg.
        runCatching { fd.close() }
    }

    /** Holt den nächsten Chunk; false heißt Ende und keine neuen Samples. */
    private fun pull(): Boolean {
        if (eos) return false
        return if (codec == null) pullRaw() else pullDecoded()
    }

    /**
     * WAV: kein Codec im Weg, `readSampleData` liefert bereits PCM.
     *
     * Hier ist das Track-Format die einzige und damit die richtige Quelle für
     * Rate, Kanäle und Encoding -- es gibt kein outputFormat.
     */
    private fun pullRaw(): Boolean {
        val chunk = rawChunk!!
        while (true) {
            chunk.clear()
            val size = extractor.readSampleData(chunk, 0)
            if (size < 0) {
                eos = true
                return false
            }
            extractor.advance()
            // shortcut: ein Raw-Chunk über 1 MB würde abgeschnitten; MediaExtractor
            // liefert WAV in Häppchen von 32..256 KB, deshalb reicht der Puffer.
            val usable = minOf(size, chunk.capacity())
            if (usable <= 0) continue
            chunk.position(0)
            chunk.limit(usable)
            if (convert(chunk, usable) > 0) return true
        }
    }

    /** MP3/AAC/Vorbis/Opus/FLAC: Eingabe an den Codec, Ausgabe als PCM einsammeln. */
    private fun pullDecoded(): Boolean {
        val c = codec!!
        var idle = 0
        while (true) {
            if (!inputDone) {
                val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val input = c.getInputBuffer(inIdx)!!
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> formatDirty = true

                outIdx < 0 -> {
                    // INFO_TRY_AGAIN_LATER: der Decoder braucht noch Eingabe oder
                    // rechnet. Nach EOS darf er sich nicht ewig Zeit lassen.
                    if (inputDone && ++idle > MAX_IDLE_TRIES) {
                        eos = true
                        return false
                    }
                }

                else -> {
                    // outputFormat ist auch ohne Formatumstellung die Wahrheit:
                    // es ist dann das beim configure() verlangte Format.
                    if (formatDirty) {
                        readOutputFormat(c)
                        formatDirty = false
                    }
                    val output = c.getOutputBuffer(outIdx)!!
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    val frames = convert(output, info.size)
                    c.releaseOutputBuffer(outIdx, false)
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    if (frames > 0) return true
                    if (eos) return false
                }
            }
        }
    }

    private fun readOutputFormat(c: MediaCodec) {
        val f = c.outputFormat
        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        lfeSlot = lfeSlotFor(f.getInteger(MediaFormat.KEY_CHANNEL_MASK, 0))
    }

    /**
     * Interleaving folgt der Bitreihenfolge der Kanalmaske, LFE ist 0x20 -- er
     * steht also an der Stelle der Anzahl gesetzter Bits darunter. Ohne
     * Maskenangabe wird nicht ausgeschlossen: bei Musikdateien ist die Maske
     * meist leer und Stereo hat ohnehin keinen LFE.
     */
    private fun lfeSlotFor(mask: Int): Int {
        if (channels <= 2) return -1
        if (mask and AudioFormat.CHANNEL_OUT_LOW_FREQUENCY == 0) return -1
        var slot = 0
        var bit = AudioFormat.CHANNEL_OUT_FRONT_LEFT
        while (bit < AudioFormat.CHANNEL_OUT_LOW_FREQUENCY) {
            if (mask and bit != 0) slot++
            bit = bit shl 1
        }
        return if (slot < channels) slot else -1
    }

    /**
     * Rechnet einen PCM-Chunk in Mono-Floats nach [pending]; Rückgabe der
     * geschriebenen Sample-Zahl.
     *
     * Ein Rahmen, der über die Chunk-Grenze ragt, wird verworfen -- das kann
     * nur bei abgeschnittenen Dateien passieren und wäre ein Sample Bruchteil.
     */
    private fun convert(buffer: ByteBuffer, size: Int): Int {
        // Handy-ABIs sind durchweg little endian; der ByteBuffer aus dem Codec
        // trägt aber Big-Endian als Vorgabe und würde sonst jedes Sample drehen.
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> throw IOException("Nicht unterstütztes PCM-Encoding: $encoding")
        }
        val frameBytes = sampleBytes * channels
        val frames = size / frameBytes
        if (frames <= 0) return 0
        ensurePending(frames)

        val base = buffer.position()
        val ch = channels
        val voices = if (lfeSlot >= 0) ch - 1 else ch
        var out = 0
        var f = 0
        while (f < frames) {
            val p = base + f * frameBytes
            var sum = 0f
            var c = 0
            while (c < ch) {
                if (c != lfeSlot) {
                    sum += when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT ->
                            buffer.getShort(p + 2 * c) / 32768f
                        AudioFormat.ENCODING_PCM_FLOAT ->
                            buffer.getFloat(p + 4 * c)
                        else -> {
                            // 24 Bit little endian, feste drei Bytes, vorzeichenbehaftet.
                            val q = p + 3 * c
                            var v = (buffer.get(q).toInt() and 0xFF) or
                                ((buffer.get(q + 1).toInt() and 0xFF) shl 8) or
                                ((buffer.get(q + 2).toInt() and 0xFF) shl 16)
                            if (v and 0x800000 != 0) v = v or -0x1000000
                            v / 8388608f
                        }
                    }
                }
                c++
            }
            pending[out++] = sum / voices
            f++
        }
        pendingPos = 0
        pendingCount = out
        return out
    }

    private fun ensurePending(size: Int) {
        if (pending.size < size) pending = FloatArray(maxOf(size, pending.size * 2))
    }

    companion object {

        /** Dekodier-Schritt: zehn Millisekunden, wie im Bericht. */
        private const val TIMEOUT_US = 10_000L

        /** Eingabepuffer für komprimierte Frames; AAC/MP3 bleiben weit darunter. */
        private const val INPUT_CHUNK = 64 * 1024

        /** Lesepuffer für unkomprimiertes PCM. */
        private const val RAW_CHUNK = 1 shl 20

        /** Startgröße des Mono-Puffers; wächst nur, wenn ein Chunk größer ist. */
        private const val PENDING_START = 8192

        /** Nach EOS so viele leere Abfragen, dann gilt der Decoder als hängen. */
        private const val MAX_IDLE_TRIES = 500

        /**
         * Öffnet die Datei und liefert eine fertig konfigurierte Quelle.
         *
         * Blockiert kurz (ein Ausgabepuffer wird dekodiert), damit [sampleRate]
         * beim Zurückkehren die echte Rate des Decoders ist und nicht die des
         * Track-Headers. Deshalb nicht vom Hauptthread aufrufen.
         */
        fun open(context: Context, uri: Uri): MediaAudioSource {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Datei nicht lesbar: $uri")
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val ex = MediaExtractor()
                extractor = ex
                // Der Deskriptor lebt bis close(); setDataSource merkt sich nur die Nummer.
                ex.setDataSource(fd.fileDescriptor)

                var track = -1
                for (t in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("audio/")) {
                        track = t
                        break
                    }
                }
                if (track < 0) throw IOException("Keine Audiospur in $uri")
                ex.selectTrack(track)

                val trackFormat = ex.getTrackFormat(track)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!

                val source: MediaAudioSource
                if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    source = MediaAudioSource(ex, null, fd)
                    source.adoptRawTrack(trackFormat)
                } else {
                    val decoder = try {
                        MediaCodec.createDecoderByType(mime)
                    } catch (e: IllegalArgumentException) {
                        // Bericht: ALAC/APE haben keinen Plattform-Decoder. Der Fang
                        // liefert dieselbe klare Meldung wie eine MediaCodecList-Prüfung,
                        // deckt aber auch jeden anderen fehlenden Decoder ab.
                        throw IOException("Kein Decoder für $mime (ALAC/APE fehlen auf Android)", e)
                    }
                    codec = decoder
                    // Der Wunsch nach PCM16 ist nur ein Wunsch; was wirklich kommt,
                    // steht später in outputFormat.
                    val want = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_RAW,
                        trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    ).apply {
                        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_CHUNK)
                    }
                    decoder.configure(want, null, null, 0)
                    decoder.start()
                    source = MediaAudioSource(ex, decoder, fd)
                }
                source.prime()
                return source
            } catch (t: Throwable) {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
                runCatching { fd.close() }
                throw t
            }
        }
    }

    private fun adoptRawTrack(trackFormat: MediaFormat) {
        sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        encoding = trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        lfeSlot = lfeSlotFor(trackFormat.getInteger(MediaFormat.KEY_CHANNEL_MASK, 0))
    }

    /**
     * Dekodiert den ersten Puffer vorab. Danach ist [sampleRate] vom Decoder
     * bestätigt, und der Aufrufer kann Fenster und Bin-Breite daraus bauen.
     */
    private fun prime() {
        if (!pull()) throw IOException("Datei enthält kein lesbares Audio")
    }
}
