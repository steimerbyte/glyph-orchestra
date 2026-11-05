# Berater-Bericht: DspFrontend

## plan

# Audio-Frontend „Glyph Orchester" — Mathematik, Parameter, Pseudocode

Scope: Datei → Band-Hüllkurven + Onsets. Kein Rendering, kein Glyph-SDK.
Zielgerät: Nothing Phone 3a Pro, 4 Kerne, Kotlin/ART ohne JNI, Hintergrundthread.

## 0. Entscheidung in einem Satz

MediaExtractor+MediaCodec → PCM16 → **Mono-Downmix per Mittelwert** → **kein Resampling** → Hann-Fenster N = nächste Zweierpotenz zu 46 ms (2048 @ 44,1/48 kHz), Hop N/4 (512, 86,1 fps) → iterative radix-2-FFT in Eigenbau (~120 Zeilen) → Leistungsspektrum |X|² → 16 logarithmische Dreiecksbänder 100–11 025 Hz (plus 6 breite Onset-Bänder) → dB mit Hann-Normierung → Spektralfluss + SuperFlux-Max-Filter + Peak-Picking.

Warum kein Resampling: 22,05 kHz/1024 und 44,1 kHz/2048 haben **exakt dieselbe Bin-Breite 21,533 Hz** (22050/1024 = 44100/2048). Die Bandmatrix ist in beiden Fällen identisch, nur die Zahl der FFT-Butterflies unterscheidet sich (112 640 vs. 51 200 Flops/Frame). Der Resampler kostet einen 65-Tap-FIR (0,34 G MAC pro 4-min-Song), einen zweiten Codepfad für 48 kHz und Aliasing-Risiko — und spart pro Song ~0,9 s FFT, die wir nicht brauchen. **Weglassen.** (Wenn ein Gerät zu langsam ist: 22,05 kHz halbieren ist der erste Hebel, s. §6.4.)

## 1. Dekodieren

### 1.1 Warum MediaExtractor + MediaCodec

| Weg | PCM? | Urteil |
|---|---|---|
| MediaPlayer | nein | liefert nur Visualizer: 128-Punkt-FFT, 8-bit-Waveform, **nur während echter Wiedergabe** (4 min = 4 min), an Ausgaberouting gebunden, teils RECORD_AUDIO nötig. Für Offline-Analyse unbrauchbar. |
| Media3/ExoPlayer-Transformer | ja (AudioProcessor-Tap) | funktioniert, aber ~1,5 MB APK + eigener Threading-Stack für null Gewinn. Nur sinnvoll, wenn die App auch Audio *schreiben* soll. |
| AudioRecord | nur Mikrofon | — |
| **MediaExtractor + MediaCodec** | ja | kanonischer Weg, plattformbeschleunigt, null Dependencies. |

Formatabdeckung der Plattform-Decoder (Android 14/15): MP3 (`audio/mpeg`) ✓, AAC-LC/HE in M4A (`audio/mp4a-latm`) ✓, Vorbis (`audio/vorbis`) ✓, Opus in Ogg (`audio/opus`) ✓ ab Android 10, FLAC ✓, WAV als `audio/raw` ✓ (kein Codec nötig). **ALAC/APE haben keinen Plattform-Decoder** → vor dem Start per `MediaCodecList` prüfen und mit klarer Meldung ablehnen, nicht mit einem Crash aus `createDecoderByType`.

### 1.2 Ablauf

```kotlin
val ex = MediaExtractor()
val pfd = resolver.openFileDescriptor(uri, "r")!!   // offen halten bis Decode fertig!
ex.setDataSource(pfd.fileDescriptor)
val track = (0 until ex.trackCount).first {
    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
}
ex.selectTrack(track)
val tf = ex.getTrackFormat(track)
var fs = tf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
var ch = tf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
val mime = tf.getString(MediaFormat.KEY_MIME)!!
val raw = (mime == MediaFormat.MIMETYPE_AUDIO_RAW)   // WAV: direkt lesen

val codec = if (raw) null else MediaCodec.createDecoderByType(mime).apply {
    val want = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, fs, ch)
    want.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) // Wunsch, nicht Garantie
    want.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
    configure(want, null, null, 0); start()
}
var enc = tf.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
```

Decode-Schleife (synchrone API, ein Hintergrundthread):

```
loop:
  inIdx = codec.dequeueInputBuffer(10ms)
  if inIdx >= 0:
     n = ex.readSampleData(codec.getInputBuffer(inIdx), 0)
     if n < 0: codec.queueInputBuffer(inIdx, 0,0,0, BUFFER_FLAG_END_OF_STREAM)
     else:     codec.queueInputBuffer(inIdx, 0,n, ex.sampleTime, 0); ex.advance()
  outIdx = codec.dequeueOutputBuffer(info, 10ms)
  if outIdx == INFO_OUTPUT_FORMAT_CHANGED:
     f = codec.outputFormat                      // AUTORITATIV
     fs = f.getInteger(KEY_SAMPLE_RATE); ch = f.getInteger(KEY_CHANNEL_COUNT)
     enc = f.optInt(KEY_PCM_ENCODING, enc)
     bandMatrix = buildBands(fs)                 // Neuaufbau, falls fs sich ändert
  else if outIdx >= 0:
     emit(convert16(codec.getOutputBuffer(outIdx), info, ch))   // mono floats
     codec.releaseOutputBuffer(outIdx, false)
     if info.flags and BUFFER_FLAG_END_OF_STREAM: break
```

Die Schnittstelle zum Rest ist bewusst schmal: `interface PcmSource { fun read(dst: FloatArray): Int }` — liefert mono floats, egal ob WAV direkt oder Decoder. Damit ist der gesamte Frontend-Code testbar ohne Android (FloatArray rein).

### 1.3 Sample-Rate und Kanäle

`tf` ist ein **Wunsch**, `codec.outputFormat` ist die Wahrheit. Drei reale Abweichungen:
- **HE-AAC (SBR)**: Track sagt 22 050 Hz, Decoder liefert 44 100 Hz.
- **Parametric Stereo**: Track sagt 1 Kanal, Decoder liefert 2.
- **Float-Output**: manche Decoder setzen `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT` (üblich bei Hi-Res/FLAC).

Deshalb: `ch`, `fs` und `enc` ausschließlich aus dem Output-Format lesen; bei `INFO_OUTPUT_FORMAT_CHANGED` Puffergeometrie neu berechnen (ein `ByteBuffer` mit anderem Layout als angenommen ist der klassische Crash).

### 1.4 Downmix auf Mono

Mit `C` Kanälen und Interleaving `s[i·C+c]`:

$$x[i] = \frac{1}{C}\sum_{c=0}^{C-1} s[i\cdot C+c] \cdot \frac{1}{32768}$$

Für `ENCODING_PCM_FLOAT` entfällt `/32768`. Für 24-bit packed (WAV, `ENCODING_PCM_24BIT_PACKED`) drei Bytes little-endian zusammensetzen und durch `2^23` teilen.

Begründung Mittelwert statt Energiesumme `(L+R)/\sqrt{2}`: bei korrelierten Kanälen (Stimme, Bass, alles mittig) würde die Energiesumme um +3 dB übersteuern; der Mittelwert ist der Standard (librosa `to_mono`) und erhält 0 dBFS als Referenz. Der Pegelunterschied zu einer energietreuen Summe ist ein **konstanter Faktor** und fällt in allen folgenden Größen heraus, weil jede Bandstufe logarithmiert und der Onset-Schwellwert auf derselben Pipeline kalibriert wird.

Sonderfall >2 Kanäle: `KEY_CHANNEL_MASK` prüfen; LFE (`CHANNEL_OUT_LOW_FREQUENCY`) ausschließen, sonst kippt der Bass um +10 dB. Bei Musikdateien praktisch nie relevant, bei Filmton schon.

### 1.5 Resampling 44,1 kHz → ?

**Empfehlung: gar nicht.** Die Begründung ist keine Bequemlichkeit, sondern eine Rechnung (Tabelle in §0): Zielauflösung 21,5 Hz ist bei 44,1 kHz mit N = 2048 und bei 22,05 kHz mit N = 1024 identisch; die Zeitauflösung ist über Hop = N/4 ebenfalls identisch. Es gibt also **keinen qualitativen Gewinn**, nur 2,2× weniger Flops.

Wenn du trotzdem halbieren willst (schwaches Gerät), ist der einzige korrekte Weg ein Halbband-Tiefpass **vor** dem Dezimieren:

$$h[n] = 0{,}5\cdot\mathrm{sinc}\big(0{,}5(n-M)\big)\cdot w_{\mathrm{Blackman}}(n),\quad n=0\ldots 2M,\ M=32$$
$$y[m] = \sum_{k=0}^{2M} h[k]\,x[2m-k],\qquad \sum_k h[k] = 1$$

Grenzfrequenz exakt 11 025 Hz (= neue Nyquist-Frequenz), 65 Taps, Sperrdämpfung ≈ 74 dB (Blackman), Gruppenlaufzeit 32 Samples = 0,73 ms (konstant, für Onsets irrelevant, aber alle Zeitstempel um diesen Betrag verschieben, wenn du gegen eine Referenz vergleichst). Kosten: 22 050 · 65 = **1,43 M MAC/s**, für den 4-min-Song 0,34 G MAC. Ohne diesen Filter faltet alles zwischen 11 und 22 kHz in das Nutzband — bei Musik mit Becken/Hi-Hats ist das kein theoretisches Problem, sondern hörbar falsche Fluss-Werte.

Für 48 kHz: nicht rational auf 22,05 umrechnen (147:320), sondern ÷2 auf 24 kHz (gleicher Filter) → N = 1024, Δf = 23,44 Hz, 9 % gröber, egal. Oder — einfacher — 48 kHz nativ mit N = 2048 analysieren und nur die Bänder oberhalb 11 kHz ignorieren.

### 1.6 Analyse-Rate: Empfehlung

**Nativ lassen (44,1 oder 48 kHz), Bänder bei 11 025 Hz abschneiden.**

| fs | N | Hop | fps | Δf | Flops/FFT |
|---|---|---|---|---|---|
| 22 050 | 1024 | 256 | 86,1 | 21,53 Hz | 51 200 |
| 44 100 | 2048 | 512 | 86,1 | 21,53 Hz | 112 640 |
| 48 000 | 2048 | 512 | 93,8 | 23,44 Hz | 112 640 |
| 96 000 | 4096 | 1024 | 93,8 | 23,44 Hz | 245 760 |

Regel statt Tabelle: `N = nächsteZweierpotenz(fs / 21,5)`, `H = N/4`. Ergibt für jede Rate ein ~46-ms-Fenster, ~11,6-ms-Hop, ~86 fps und 21,5–31 Hz Bin-Breite. Kein Sonderfall im Code.

## 2. Framing

### 2.1 Fenster

Periodisches Hann (nicht symmetrisch — für STFT die richtige Variante):

$$w[n] = \tfrac{1}{2}\Big(1-\cos\tfrac{2\pi n}{N}\Big),\quad n=0\ldots N-1,\qquad S=\sum_n w[n]=\frac{N}{2}$$

Vergleich Hamming: $w_H[n]=0{,}54-0{,}46\cos(2\pi n/N)$, erster Nebenkeulensattel −43 dB (Hann −31,5 dB), aber Abfall nur 6 dB/Oktave gegen **18 dB/Oktave** bei Hann, und $w_H[0]=0{,}08$ erzeugt eine Sprungstelle am Fensterrand. Bei Musik mit 60+ dB Dynamik ist der Abfall entscheidend: ein lauter Bass bei 80 Hz darf ein Hi-Hat bei 5 kHz nicht über die Nebenkeulen zumaskieren. Hann ist Standard in librosa, madmom und SuperFlux. Kosten identisch.

**Fenstergewinn:** $S = N/2$ exakt (Summe der Kosinusterme = 0). Damit ist die amplitude-korrekte Rückrechnung:

$$|X[k]|_{\text{Sinus, Vollaussteuerung}} = A\cdot\frac{S}{2} = A\cdot\frac{N}{4}\ \Rightarrow\ A = \frac{2\sqrt{P[k]}}{S}$$

### 2.2 Warum 1024/512 bei 22,05 kHz (und 2048/512 bei 44,1)

Gabor-Grenze: $\Delta t\cdot\Delta f \ge 1$; bei uns $46{,}4\,\mathrm{ms}\cdot 21{,}53\,\mathrm{Hz} = 1{,}00$ — das Fenster ist exakt zeit-bandbreite-optimal, kein Spielraum verschenkt.

- **N = 512 wäre zu klein**: Δf = 43 Hz. Kick-Fundament (55–80 Hz) und Bass-Grundton (85–110 Hz) liegen dann in Nachbarbins 1–2 und sind nicht trennbar; die untersten vier Bänder der 16-Band-Tabelle hätten 0–1 Bins und wären undefiniert.
- **N = 4096 wäre zu groß**: 93 ms Fenster verschmieren eine 16-tel-Note bei 180 BPM (83 ms) zu einem Frame; die Attack-Flanke wird flachgedrückt, der Fluss-Peak verbreitert sich, Onset-Jitter ±2 Frames.
- **Hop N/2 (50 %) reicht nicht für Onsets**: 23 ms Raster ist gröber als die 5–10 ms, die Onset-Detektoren brauchen (SuperFlux arbeitet mit 200 fps = 5 ms). N/4 bei 86 fps ist der Kompromiss: Onset-Zeitfehler ≤ 5,8 ms nach Sub-Frame-Interpolation, und die Punktentscheidung „blinkt / blinkt nicht" läuft danach ohnehin auf der LED-Rate (30–60 fps).

**Kein COLA nötig**: wir rekonstruieren nie Audio, Überlappung ist rein eine Frage der Zeitauflösung.

## 3. FFT

### 3.1 Komplexität

$$X[k]=\sum_{n=0}^{N-1}x[n]e^{-2\pi i kn/N},\qquad \text{radix-2 DIT: } \frac{N}{2}\log_2 N \text{ Butterflies}$$

Butterfly = 1 komplexe Multiplikation (4 mul, 2 add) + 2 komplexe Additionen (4 add) = **10 Flops**. N = 2048: 11 264 Butterflies = **112 640 Flops**. N = 1024: 5 120 = 51 200 Flops.

### 3.2 Implementierung in Kotlin

Drei Tabellen einmal pro Datei vorberechnen, **niemals pro Frame allokieren** (20 671 Frames × 4 Arrays = GC-Sturm):

```kotlin
class Fft(val n: Int) {
    val bits = Integer.numberOfTrailingZeros(n)      // n ist Zweierpotenz
    val rev = IntArray(n) { i ->
        var r = 0; for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b)); r
    }
    val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    val re = FloatArray(n); val im = FloatArray(n)

    /** fuellt re/im aus x[off..off+n) und transformiert in-place */
    fun forward(x: FloatArray, off: Int, win: FloatArray) {
        for (i in 0 until n) {                         // Fenstern + Bit-Reversal fusioniert
            val j = rev[i]; re[j] = x[off + i] * win[i]; im[j] = 0f
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len                        // Twiddle-Schrittweite
            var i = 0
            while (i < n) {
                var k = 0
                var j = i
                while (j < i + half) {
                    val c = cosT[k]; val s = sinT[k]       // w = e^{-2*pi*i*k/len}
                    val vr = re[j + half] * c + im[j + half] * s
                    val vi = im[j + half] * c - re[j + half] * s
                    val ur = re[j]; val ui = im[j]
                    re[j] = ur + vr; im[j] = ui + vi
                    re[j + half] = ur - vr; im[j + half] = ui - vi
                    k += step; j++
                }
                i += len
            }
            len = len shl 1
        }
    }
}
```

Vorzeichenprobe: $w=c-is$, $(a+bi)(c-is) = (ac+bs) + i(bc-as)$ — genau die zwei Zeilen oben. Falsches Vorzeichen im Imaginärteil ist der häufigste Fehler; Test: Rechteck-Signal muss symmetrisches Betragsspektrum mit Nullstellen bei geraden Harmonischen geben.

**Verifikation der Implementierung** (eine Datei, ein Test): Impuls bei n = 0 → alle Beträge 1; Sinus bei Bin k0 mit Vollaussteuerung → |X[k0]| = N/4, alle anderen Bins < −60 dB; Parseval: $\sum_k P[k] = N\sum_n x[n]^2$ (relativer Fehler < 1e-4 bei Float).

### 3.3 Real-FFT und JTransforms

**Real-FFT (N/2 komplex + Twiddle-Nachbearbeitung)** halbiert die Flops (51 200 statt 112 640). Verdict: **nicht machen**. Spart ~0,6 s pro Song, kostet 40 Zeilen fehleranfälligen Code und einen zweiten Testpfad. Erst wenn eine Messung zeigt, dass die FFT der Flaschenhals ist.

**JTransforms** (`com.github.wendykierp:JTransforms:3.2`, BSD-2, 29.11.2025, zieht `JLargeArrays` mit): der Klassiker lebt wieder und ist gepflegt. Verdict trotzdem **nein**: (a) 1024/2048-Punkte-FFT ist bei uns kein Engpass (§6); (b) die Bibliothek bringt einen eigenen Thread-Pool (`ConcurrencyUtils`) mit, der gegen den Android-Scheduler und unseren Hintergrundthread arbeitet — man muss ihn global konfigurieren, was in einer App ein Prozess-weiter Seiteneffekt ist; (c) zwei zusätzliche Artefakte in der APK für eine Funktion, die in 120 Zeilen steht und dabei Fenstern + Bit-Reversal + Leistungsspektrum **in einem Durchlauf** erledigt, was die Bibliothek nicht kann. Lohnt erst ab N ≥ 8192 oder wenn man DCT/Multithreading geschenkt haben will.

## 4. Spektrum

$$P[k] = \mathrm{Re}^2 + \mathrm{Im}^2,\qquad |X[k]| = \sqrt{P[k]}$$

$|X|$ nur, wenn du es anzeigst; für Bänder und Fluss reicht $P$ (spart 1024 `sqrt` pro Frame).

**dB-Skala, amplitude-korrekt** (Vollaussteuerungssinus = 0 dBFS):

$$L_{\mathrm{dB}}[k] = 10\log_{10}\!\Big(\frac{4\,P[k]}{S^{2}} + \varepsilon\Big),\quad \varepsilon = 10^{-12}\ (\text{Floor bei } {-120}\,\mathrm{dB})$$

Mit $S = N/2$ ist $4/S^2 = 16/N^2$; für N = 2048 also $L = 10\log_{10}(P/262144 + \varepsilon)$.

**Frequenzauflösung** $\Delta f = f_s/N = 21{,}533\ \mathrm{Hz}$; Bin $k$ ↔ $k\Delta f$; analysierter Bereich bis $k_{\max} = \lfloor 11025/\Delta f\rfloor = 511$ (bei 44,1 kHz von 1024 Bins — die obere Hälfte wird berechnet und weggeworfen, das ist der Preis für „kein Resampler").

**Nullpadding: nein.** Es interpoliert die DTFT, es gewinnt keine Information ($\Delta f$ bleibt $1/T$), verdoppelt die FFT-Größe für glattere Balken. Wenn Bandkanten exakt auf Bin-Mitten fallen sollen: Kante auf den nächsten Bin runden (§5.2 macht das schon).

## 5. Bänder: logarithmisch, nicht Mel

### 5.1 Warum nicht Mel

$$m(f) = 2595\log_{10}\!\Big(1+\frac{f}{700}\Big),\qquad f(m) = 700\big(10^{m/2595}-1\big)$$

24 Mel-Bänder über 0–11 025 Hz ergeben (gerechnet, nicht geschätzt):

```
0, 87, 185, 296, 420, 559, 716, 893, 1091, 1314, 1565, 1847,
2165, 2522, 2923, 3375, 3883, 4454, 5096, 5818, 6630, 7544, 8571, 9726, 11025 Hz
```

Das heißt: **8 von 24 Bändern liegen unter 1,1 kHz**, und das oberste ist 1 299 Hz breit. Drei Gründe, warum das für uns falsch ist:

1. **Falsche Auflösungsverteilung für Transienten.** Onset-Information sitzt in breitbandigen Attack-Rauschanteilen zwischen 2 und 11 kHz. Mel legt dort 8 Bänder hin, unter 1 kHz 8 Bänder — genau umgekehrt zum Bedarf. Ein Hi-Hat bei 8 kHz fällt in ein 1,2-kHz-Band und wird mit Becken und Sibilanten verschmolzen: der Fluss-Peak wird breit und niedrig.
2. **Der Stand der Technik macht es anders.** SuperFlux/LogFiltSpecFlux (Böck & Widmer, DAFx-13) nutzt eine **logarithmische** Filterbank mit Viertelton-Abstand, 138 Filter von 27,5 bis 16 000 Hz, und begründet das explizit: bei konstanter relativer Bandbreite ist eine Tonhöhenverschiebung (Vibrato) immer derselbe Bin-Versatz, weshalb der Max-Filter-Tracker mit fester Suchbreite funktioniert. Mel ist dafür nicht konstruiert.
3. **LED-Mapping.** Bei Mel sind LEDs 1–8 alle „Bass", oben wird es grob. Für einen Streifen, der von unten (tief) nach oben (hoch) leuchten soll, braucht man monotone, perzeptiv gleich breite Schritte → logarithmisch.

Mel ist für MFCC/Spracherkennung richtig, für Onset-Bänder nicht.

### 5.2 Bandsatz A — Visualisierung, 16 Bänder, 100–11 025 Hz

$$f_i = 100\cdot r^{\,i},\quad r = \Big(\frac{11025}{100}\Big)^{1/16} = 1{,}3417\ (0{,}424\ \text{Oktave}),\ i=0\ldots 16$$

```
B00  100,0– 134,2 Hz  Bins   5– 6 (2)
B01  134,2– 180,0      Bins   7– 8 (2)
B02  180,0– 241,5      Bins   9–11 (3)
B03  241,5– 324,0      Bins  12–15 (4)
B04  324,0– 434,8      Bins  16–20 (5)
B05  434,8– 583,3      Bins  21–27 (7)
B06  583,3– 782,6      Bins  28–36 (9)
B07  782,6–1050,0      Bins  37–48 (12)
B08 1050,0–1408,8      Bins  49–65 (17)
B09 1408,8–1890,1      Bins  66–87 (22)
B10 1890,1–2535,9      Bins  88–117 (30)
B11 2535,9–3402,4      Bins 118–158 (41)
B12 3402,4–4564,9      Bins 159–211 (53)
B13 4564,9–6124,6      Bins 212–284 (73)
B14 6124,6–8217,3      Bins 285–381 (97)
B15 8217,3–11025,0     Bins 382–512 (131)
```

(Bins gelten für Δf = 21,533 Hz, also fs/N = 44 100/2048 **und** 22 050/1024. Für 48 kHz verschieben sich die Bin-Indizes um 9 % — deshalb Bin-Grenzen aus Hz zur Laufzeit berechnen, Tabelle nur als Erwartungswert.)

Für die 12 LED-Zonen des Glyph Interface: 12 Bänder, 80–11 025 Hz, $r = 1{,}5076$ (0,592 Okt), kleinste Bandbreite 2 Bins (80–120,6 Hz). Beides valide; **Default 16**, weil man beim Mischen auf 12 Zonen benachbarte Bänder energieaddieren kann, aber nicht teilen.

### 5.3 Bandsatz B — Onset, 6 Bänder

An Drum-Registern orientiert (nicht perzeptiv, sondern instrumentenkundlich):

| # | Hz | Bins @21,53 Hz | Inhalt |
|---|---|---|---|
| 0 | 30–120 | 2–5 | Kick-Fundament |
| 1 | 120–350 | 6–16 | Snare-Körper, Bass-Obertöne |
| 2 | 350–1000 | 17–46 | Toms, Gesang-Grundton |
| 3 | 1000–3000 | 47–139 | Snare-Crack, Präsenz |
| 4 | 3000–7000 | 140–325 | Hi-Hats, Cymbal-Attack |
| 5 | 7000–11025 | 326–512 | Sizzle, Air |

Die 6 Bänder dienen nur der **Klassifikation** (welche LED-Gruppe zuckt), nicht der Detektion — die läuft breitbandig.

### 5.4 Dreiecksfilter und Bandenergie

$$\varphi_b(f)= \begin{cases} \dfrac{f-f_{b-1}}{f_b-f_{b-1}} & f_{b-1}\le f\le f_b\\[4pt] \dfrac{f_{b+1}-f}{f_{b+1}-f_b} & f_b< f\le f_{b+1}\\[4pt] 0 & \text{sonst}\end{cases}$$

$$E_b = \frac{1}{n_b}\sum_{k=k_{b}^{\mathrm{lo}}}^{k_{b}^{\mathrm{hi}}}\varphi_b(k\Delta f)\,P[k]$$

Praxis: die Matrix `tris: Array<FloatArray>(B) { FloatArray(kMax+1) }` **einmal** bauen, dicht (16 × 512 × 4 B = 32 KB, passt in L2), pro Frame 8 192 MAC. Kosten vernachlässigbar; Sparse-Repräsentation erst, wenn ein Profiler es verlangt. `n_b` = Anzahl Bins im Band (Flächennormierung).

Wichtige Einsicht: **für den Onset-Fluss ist die Flächennormierung irrelevant** — im Logarithmus ist $\log(aE_b)-\log(aE_{b,\mathrm{prev}})$ unabhängig von $a$. Sie zählt nur für die Anzeige (dB-Balken vergleichbar über Bänder).

## 6. Rechenbudget

### 6.1 Pro Sekunde Audio (44,1 kHz, N = 2048, H = 512)

| Posten | Menge/s |
|---|---|
| Frames | 86,1 |
| FFT-Butterflies | 970 000 |
| FFT-Flops | 9,7 M |
| Fenstern (in Bit-Reversal gefaltet) | 176 000 mul |
| Betragsquadrat | 88 000 mul+add (1 024 Bins) |
| Bandmatrix | 706 000 MAC |
| `log10` | 1 378 (16 Bänder × 86) |
| Decoder | 44 100 × 2 Kanäle Samples |

Ein Frame: 112 640 (FFT) + 2 048 (Betrag) + 8 192 (Bänder) ≈ **123 k Flops**.

### 6.2 4-Minuten-Song (240 s)

- Frames: **20 672**
- Flops: 20 672 × 123 k ≈ **2,54 Gflop**
- Raster: 86,1 fps × 240 s
- PCM-Rohdaten: 240·44 100·2·2 B = **42,3 MB** → niemals komplett halten, streamen.
- Ausgabe: 20 672 × 16 Bänder × 4 B = **1,32 MB** (+ 20 672 Floats Fluss = 83 KB). Ein volles Spektrogramm wäre 20 672 × 513 × 4 B = **42 MB** — genau die Falle, die eine 4-min-App zum Absturz bringt. Nur Bandhüllkurven behalten.

**Zeitschätzung** [INFERENCE, nicht auf dem Gerät gemessen]: ART-JIT auf einem 2,4-GHz-Kern schafft für solche FloatArray-Schleifen etwa 0,9–1,9 Gflop/s (skalar; ART vektorisiert diese Schleifen nicht). Damit:

| Posten | Zeit |
|---|---|
| FFT + Bänder (1 Thread) | 1,3–2,8 s |
| Decode (MP3/AAC, Hardware/Software-Decoder) | 0,5–1,5 s [INFERENCE] |
| **Summe** | **1,8–4,3 s** |

→ **< 5 s ist mit einem Thread erreichbar, aber ohne Reserve.** Zwei Hebel, in dieser Reihenfolge:
1. **Zwei Worker-Threads** über Frame-Blöcke (Frames sind unabhängig; je Block ein eigenes `Fft`-Objekt, Ergebnisse in ein vorallokiertes Array schreiben — keine Synchronisation nötig). Halbiert die 1,3–2,8 s. Decode bleibt seriell, also Gesamt ≈ 1,2–2,2 s.
2. 22,05 kHz/1024 (Resampler aus §1.5) halbiert die FFT-Flops, kostet 0,35 s FIR → netto −0,5 s. Weniger Gewinn als 1., mehr Code.

Empfehlung: **ein Thread implementieren, `System.nanoTime` um die Frameschleife loggen, und erst wenn die Messung > 3 s zeigt, Punkt 1 nachziehen.** Blockgröße fürs Parallelisieren: 10 s Audio pro Block (441 000 Samples = 1,8 MB float) — begrenzt den Speicher und hält die Abbruchprüfung responsiv.

Pessimistischer Fall (A53-Klasse, 4× langsamer): 8–17 s → dann ist die App auf dem Gerät nicht „unter 5 s", und nur dort greift Parallelisierung + Halbierung. Deshalb Fortschritt als `(frameIndex / totalFrames)` melden, nicht als unbestimmten Spinner.

### 6.3 Speicher (Arbeitsstand)

| Puffer | Größe |
|---|---|
| Ringpuffer 1 s mono float | 176 KB |
| `re`, `im` (2 × 2048 f) | 16 KB |
| Hann + Twiddles + `rev` (2048 f + 2048 f + 2048 i) | 24 KB |
| Bandmatrix 16 × 512 f | 32 KB |
| `logPrev` 16 f, Ausgabe 1,3 MB | 1,3 MB |

Arbeitsstand < 250 KB + Ausgabe. Kein Objekt pro Frame, keine Boxes, `FloatArray` statt `List<Float>`.

## 7. Onset-Erkennung (Parameter, die die Bandwahl begründen)

Klassischer Spektralfluss mit Max-Filter-Trajektorie (SuperFlux, Böck & Widmer DAFx-13), angepasst auf 86 fps:

1. **Bandpegel** $L_b[t] = 20\log_{10}(A_b[t]+10^{-4})$, dann pro Frame auf $[\max_b L_b - 60,\ \max_b L_b]$ begrenzen. Das ist die Pegel-Begrenzung, die verhindert, dass ein Frame mit Grundrauschen (−90 dB) einen Riesenfluss erzeugt, weil dort jede 3-dB-Schwankung 3 Einheiten zählt.
2. **Stille-Gate**: ist $\max_b L_b < -70$ dBFS, setze Fluss = 0.
3. **Max-Filter über die Frequenz** (3 Bänder: b−1, b, b+1, im *aktuellen* Frame) — unterdrückt Vibrato-Fehldetektionen um bis zu 60 % (Paper).
4. **Fluss**: $\mathrm{SF}[t] = \frac{1}{B}\sum_b \max\big(0,\ L_b[t] - L_b^{\max}[t-\lambda]\big)$, $\lambda = 1$ Frame (11,6 ms). Das Paper nutzt λ = 2 bei 200 fps; bei 86 fps wäre λ = 2 bereits 23 ms Basislinie und würde die Werte halbieren.
5. **Peak-Picking** (Paper-Parameter, in Frames umgerechnet bei 86 fps):
   - $\mathrm{SF}[t] = \max(\mathrm{SF}[t-3 \ldots t+3])$ (±30 ms)
   - $\mathrm{SF}[t] \ge \mathrm{mean}(\mathrm{SF}[t-9 \ldots t+6]) + \delta$ (−100 ms…+70 ms), $\delta$ = 0,5 als Startwert, auf dem Gerät nach Gehör tunen
   - $t - t_{\text{prev}} > 3$ Frames (30 ms Mindestabstand, d. h. max. 33 Onsets/s)
   - Onset-Zeit $= t\cdot H/f_s$; die Fenstermitte liegt $N/2$ Samples vor dem Frame-Ende → $t_{\text{onset}} \approx (t\cdot H + N/2)/f_s$ (konstanter Versatz von 23 ms, sonst wirkt alles „zu spät").

**Fallback für schwache Geräte**: energiebasierter Onset (RMS pro Frame, gleicher Fluss + gleiches Peak-Picking) kostet ~50× weniger (keine FFT) und ist für percussive Musik brauchbar, versagt aber bei weichen Streichern/Flöten — genau deshalb ist die FFT der Default.

## 8. API-Skizze für den Entwickler

```kotlin
interface PcmSource { fun read(dst: FloatArray): Int; fun close() }   // mono, -1..1

class AudioFrontend(private val fs: Int) {
    val n: Int = nearestPow2(fs / 21.5f); val hop = n / 4
    val frameRate = fs / hop.toDouble()               // 86.1
    data class Bands(val gainDb: FloatArray)          // Länge 16
    data class Result(
        val bandEnvDb: Array<FloatArray>,             // [frame][16], ~1.3 MB für 4 min
        val flux: FloatArray,
        val onsetsSec: FloatArray,
        val frameRate: Double,
        val sampleRate: Int
    )
    fun analyze(src: PcmSource, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Result
}
```

Pipeline im Inneren: `read()` → Ringpuffer → solange ≥ N Samples: Fenster → FFT → P → Bänder → dB → Fluss → weiter mit `hop`. Abbruch zwischen Frames prüfen (`isCancelled()` einmal pro 100 Frames, nicht jedes Mal).

## 9. Empfehlungskette (die Zahlen zum Abnicken)

1. `MediaExtractor` + `MediaCodec`, PCM16 (oder Float), `fs`/`ch`/`enc` **nur** aus `codec.outputFormat`.
2. Mono per Mittelwert `/C`; LFE ausschließen, falls `KEY_CHANNEL_MASK` es nennt.
3. **Kein Resampling.** `N = nächsteZweierpotenz(fs/21,5)` → 2048 bei 44,1/48 kHz; `H = N/4` → 512, 86,1 fps, Δf 21,53 Hz.
4. Periodisches Hann, `S = N/2`, Amplitudennormierung `A = 2√P/S`.
5. Eigene iterative radix-2-FFT, Bit-Reversal mit dem Fenstern fusioniert, Twiddle-Tabellen vorab, keine Allokation im Loop. Kein JTransforms, kein Real-FFT-Trick.
6. Leistungsspektrum `P = Re²+Im²`, dB `10log10(16P/N² + 1e-12)`, kein Nullpadding, Bänder bis Bin 511 (11 025 Hz).
7. 16 logarithmische Dreiecksbänder 100→11 025 Hz (r = 1,3417, 0,424 Okt, min. 2 Bins) für die Anzeige; 6 feste Bänder für die Onset-Klassifikation; **kein Mel**.
8. Onset: 60-dB-Klemme pro Frame, Max-Filter über 3 Bänder, λ = 1 Frame, Peak-Picking mit ±30 ms / −100…+70 ms / 30 ms Mindestabstand, δ ≈ 0,5.
9. Budget: 2,54 Gflop für 4 min, 2,6 M Flops/s Audio, < 250 KB Arbeitsspeicher, 1,3 MB Ausgabe — 1,8–4,3 s auf einem Kern, < 2,5 s mit zwei Workern.

## 10. Was nicht geprüft wurde

- Keine Messung auf dem Gerät (read-only, kein Gradle/adb in dieser Runde): FFT-Zeiten sind aus Flop-Zahl × realistischer ART-Rate geschätzt, Decode-Zeit aus Erfahrungswerten. Der erste Schritt der Implementierung ist ein `nanoTime`-Log um die Frameschleife.
- Glyph-Framerate und Zonenanzahl des 3a Pro kommen vom GlyphSdk-Strang; hier steht nur „LED-Rate R" und die Pooling-Empfehlung (Max-Pooling über die Analyseframes, nicht Mittelwert — sonst verschwinden Attacken).
- SuperFlux-Konstanten (30/30/100/70 ms, Max-Filter 3 Bins, λ) stammen aus dem Paper für 200 fps/44,1 kHz und sind hier auf 86 fps umgerechnet; δ ist Startwert, kein kalibrierter Wert.


## formulas

- Downmix: x[i] = (1/C) * sum_{c=0..C-1} s[i*C+c] / 32768 (C = Kanalzahl, s = interleaved PCM) — Mittelwert, weil Energiesumme (L+R)/sqrt(2) bei korrelierten Kanälen übersteuert und der Pegelunterschied in jeder logarithmischen Folgestufe als Konstante herausfällt.
- Hann (periodisch): w[n] = 0.5 * (1 - cos(2*pi*n/N)), n = 0..N-1; Fenstersumme S = sum w[n] = N/2 exakt. Hamming zum Vergleich: w_H[n] = 0.54 - 0.46*cos(2*pi*n/N), Abfall 6 dB/Okt statt 18 dB/Okt, Endwert 0.08 (Sprungstelle).
- Amplitudennormierung: für einen Sinus der Amplitude A bei Bin k0 ist |X[k0]| = A*S/2 = A*N/4, also A = 2*sqrt(P[k0])/S.
- DFT: X[k] = sum_{n=0..N-1} x[n] * e^(-2*pi*i*k*n/N). Radix-2-DIT: N/2*log2(N) Butterflies, ein Butterfly = 4 mul + 6 add = 10 Flops. N=2048: 11264 Butterflies = 112640 Flops. N=1024: 5120 = 51200 Flops.
- Butterfly mit w = c - i*s: vr = re[j+h]*c + im[j+h]*s; vi = im[j+h]*c - re[j+h]*s; re[j] = ur+vr; im[j] = ui+vi; re[j+h] = ur-vr; im[j+h] = ui-vi. (Falsches Vorzeichen in vi ist der Standardfehler.)
- Bit-Reversal: rev[i] = Spiegelung der log2(N) niedrigen Bits von i; wird mit dem Fenstern fusioniert: re[rev[i]] = x[off+i]*win[i], im[rev[i]] = 0. Twiddle-Index bei Stufe len: k * (N/len).
- Bandkanten: f_i = f_min * r^i mit r = (f_max/f_min)^(1/B). 16 Bänder 100..11025 Hz: r = 1.3417 (0.424 Oktave). 12 Bänder 80..11025 Hz: r = 1.5076 (0.592 Oktave).
- Dreiecksfilter: phi_b(f) = (f - f_{b-1})/(f_b - f_{b-1}) für f_{b-1} <= f <= f_b; (f_{b+1} - f)/(f_{b+1} - f_b) für f_b < f <= f_{b+1}; 0 sonst.
- Bandenergie: E_b = (1/n_b) * sum_{k=k_lo..k_hi} phi_b(k*df) * P[k]; n_b = Bin-Anzahl im Band. Für den Onset-Fluss ist die Flächennormierung wirkungslos (im Logarithmus eine additive Konstante, die in der Differenz verschwindet); sie zählt nur für die dB-Anzeige.
- dB-Skala: L[k] = 10*log10(4*P[k]/S^2 + eps) = 10*log10(16*P[k]/N^2 + 1e-12). Vollaussteuerungssinus ergibt 0 dBFS, Floor bei -120 dB.
- Frequenzauflösung: df = fs/N = 21533/1000 Hz; bei 44100/2048 und 22050/1024 identisch 21.533 Hz (deshalb ist Resampling für die Bandmatrix wirkungslos).
- Gabor: dt*df >= 1; 46.4 ms * 21.53 Hz = 1.00 (Fenster ist zeit-bandbreite-optimal).
- Dezimierfilter (nur falls 22.05 kHz gewünscht): h[n] = 0.5*sinc(0.5*(n-M))*blackman(n), M=32, 65 Taps, sum h = 1, Grenzfrequenz 11025 Hz, Gruppenlaufzeit 32 Samples = 0.73 ms; y[m] = sum_{k=0..64} h[k]*x[2m-k].
- Mel (nur zum Vergleich, nicht verwendet): m(f) = 2595*log10(1 + f/700), f(m) = 700*(10^(m/2595) - 1).
- SuperFlux-Fluss: L_b[t] = 20*log10(A_b[t] + 1e-4), geklemmt auf [max_b L_b - 60, max_b L_b]; L^max_b = max(L_{b-1}, L_b, L_{b+1}) im aktuellen Frame; SF[t] = (1/B) * sum_b max(0, L_b[t] - L^max_b[t-lambda]), lambda = 1 Frame.
- Peak-Picking: SF[t] = max(SF[t-3..t+3]) und SF[t] >= mean(SF[t-9..t+6]) + delta und t - t_prev > 3 Frames; Onset-Zeit = (t*H + N/2)/fs (N/2 = konstante Fenstermitten-Korrektur).
- Komplexität gesamt: O(F * N log N + F * N * B) mit F = fs*T/H Frames; für 240 s bei 86.1 fps: F = 20672, 2.54 Gflop.
- Pegel-Klemme gegen Rausch-Fehlonsets in leisen Passagen: pro Frame auf [max_b L_b - 60 dB, max_b L_b] begrenzen, und unter -70 dBFS Frame-Maximum Fluss = 0 setzen.

## parameters

- Analyse-Rate: native Dateirate (44.1/48 kHz), KEIN Resampling. Bandmatrix-Limit 11025 Hz (Bin 511 bei df = 21.533 Hz).
- FFT-Größe: N = nächsteZweierpotenz(fs/21.5) → 2048 bei 44.1 und 48 kHz, 1024 bei 22.05 kHz, 4096 bei 96 kHz, 512 bei 16 kHz.
- Hop: H = N/4 → 512 @ 44.1 kHz = 11.61 ms = 86.1 fps; 256 @ 22.05 kHz = 11.61 ms = 86.1 fps. (H muss keine Zweierpotenz sein: bei 48 kHz H = 512 → 93.8 fps, oder H = 557 für exakt 86 fps.)
- Fenster: periodisches Hann, N Samples, S = N/2.
- Frame-Zahl 4-min-Song: 20672 bei 86.1 fps (bei Hop 512/23.2 ms wären es 10336).
- Bandmatrix Anzeige: 16 logarithmische Dreiecksbänder 100–11025 Hz, r = 1.3417 (0.424 Oktave), min. 2 Bins pro Band, dichte Matrix 16 x 512 floats = 32 KB, 8192 MAC/Frame.
- Bandmatrix 12 LED-Zonen: 12 Bänder 80–11025 Hz, r = 1.5076 (0.592 Oktave), B00 = 80–120.6 Hz (2 Bins).
- Onset-Bänder (Klassifikation, nicht Detektion): 30–120 / 120–350 / 350–1000 / 1000–3000 / 3000–7000 / 7000–11025 Hz = Bins 2–5 / 6–16 / 17–46 / 47–139 / 140–325 / 326–512.
- Onset-Fluss: Fluss über alle 16 Bänder (breitbandig), Max-Filter über 3 Bänder, lambda = 1 Frame (23 ms wäre zu weit bei 86 fps).
- Peak-Picking: pre_max 3 / post_max 3 / pre_avg 9 / post_avg 6 Frames, Mindestabstand 3 Frames (30 ms), delta = 0.5 als Startwert (tunen).
- Pegel-Klemme: 60 dB unter dem Frame-Maximum; Stille-Gate bei -70 dBFS.
- dB-Konstante: 16/N^2 (Hann-Normierung), eps = 1e-12.
- Twiddle-Tabellen: cosT/sinT je N/2 floats (8 KB bei N=2048), rev IntArray(N), alle einmal pro Datei.
- Komplexität: 112640 Flops/Frame bei N=2048; 9.7 Mflop/s Audio; 2.54 Gflop für 4 min; 123 k Flops/Frame inkl. Bänder.
- Speicher: < 250 KB Arbeitsstand (Ringpuffer 176 KB, re/im 16 KB, Tabellen 24 KB, Bandmatrix 32 KB) + 1.32 MB Ausgabe (16 Bänder x 20672 Frames x 4 B); volles Spektrogramm wäre 42 MB und ist verboten.
- Threading: 1 Hintergrundthread (Dispatchers.Default) reicht; bei Messung > 3 s Blockweise auf 2 Worker über 10-s-Blöcke (441000 Samples = 1.8 MB) parallelisieren, Decode bleibt seriell.
- Datei-Formate: MP3 (audio/mpeg), AAC/HE-AAC (audio/mp4a-latm), Vorbis, Opus in Ogg (ab Android 10), FLAC, WAV (audio/raw, kein Codec) — ALAC/APE vorab per MediaCodecList ablehnen.
- PCM-Encoding: 16-bit LE (asShortBuffer) und FLOAT (asFloatBuffer) implementieren; 24-bit packed (3 Byte LE / 2^23) nur für WAV nötig.
- Ziel-Framerate des LED-Renderers: Pooling der Analyseframes per MAXIMUM, nicht Mittelwert (sonst verschwinden Attacken); Analyse mit 86 fps liefert dafür 1.4–2.9 Frames pro Ausgabeframe.

## pitfalls

- codec.outputFormat ist die Wahrheit, nicht das Track-Format: HE-AAC verdoppelt die Sample-Rate (22050 -> 44100), Parametric Stereo ändert die Kanalzahl, manche Decoder liefern FLOAT statt 16-bit. Wer fs/ch/enc einmal aus dem Track-Format liest, bekommt bei diesen Dateien falsche Fenster und Interleaving-Crashs. Bei INFO_OUTPUT_FORMAT_CHANGED alles neu aufbauen (auch die Bandmatrix).
- Der FileDescriptor aus ContentResolver.openFileDescriptor muss bis zum Ende des Decodes offen bleiben; ein 'use { }' um setDataSource schließt ihn zu früh und der Extractor liest danach Müll oder wirft.
- MediaCodec nie pro Chunk neu erzeugen und immer in finally freigeben (release + stop); ein geleakter Codec blockiert beim nächsten Song den Hardware-Decoder.
- Kein Resampling: 44.1/2048 und 22.05/1024 haben identische Bin-Breite (21.533 Hz) — der Resampler bringt keinen Qualitätsgewinn, kostet 65-Tap-FIR (0.34 G MAC pro 4-min-Song) und braucht zwingend den Anti-Aliasing-Filter VOR dem Dezimieren (sonst faltet 11–22 kHz ins Nutzband: Hi-Hats erzeugen Phantom-Onsets).
- Nicht 512 als FFT-Größe: df = 43 Hz macht Kick (55–80 Hz) und Bass (85–110 Hz) untrennbar und die untersten Bänder der 16-Band-Tabelle hätten 0–1 Bins. Nicht 4096: 93 ms Fenster verschmieren eine 16-tel-Note bei 180 BPM (83 ms).
- Hann periodisch (nicht symmetrisch) und Fenstergewinn S = N/2 verwenden; ohne Normierung 2*sqrt(P)/S sind die dB-Werte um 6 dB falsch und die Onset-Schwelle nicht übertragbar.
- FFT-Implementierung: Vorzeichen des Imaginärteils (w = c - i*s) und Bit-Reversal-Fusion prüfen mit drei Tests — Impuls -> alle |X| = 1; Vollaussteuerungssinus bei k0 -> |X[k0]| = N/4; Parseval sum P = N*sum x^2 (Fehler < 1e-4 bei Float).
- Keine Allokation im Frameloop: re/im/win/rev/bandMatrix einmal pro Datei, sonst 20672 Frames x 4 Arrays = ~80k Allokationen und GCruckler. FloatArray statt List<Float>, keine Lambdas pro Frame.
- Niemals das volle Spektrogramm halten: 20672 x 513 x 4 B = 42 MB (plus 42 MB PCM = OOM). Nur Bandhüllkurven (1.3 MB) und Fluss (83 KB) behalten, PCM streamen.
- Nullpadding bringt nichts: es interpoliert nur die DTFT, df bleibt 1/T, und verdoppelt die FFT. Bandkanten stattdessen auf Bin-Mitten runden.
- Mel-Filterbank ist hier falsch: 8 von 24 Mel-Bändern liegen unter 1.1 kHz und das oberste ist 1.3 kHz breit — Onset-Cues sitzen aber bei 2–11 kHz. SuperFlux nutzt deshalb eine logarithmische Viertelton-Filterbank (138 Filter, 27.5–16 kHz); Mel gehört zu MFCC/Spracherkennung.
- Fluss ohne Pegel-Klemme erzeugt Fehlonsets in leisen Passagen: bei -90 dBFS Frame-Pegel ist jede 3-dB-Schwankung 3 Fluss-Einheiten. Pro Frame auf [max-60 dB, max] klemmen und unter -70 dBFS Frame-Maximum Fluss = 0 setzen.
- Onset-Zeitstempel brauchen die Fenstermitten-Korrektur +N/2 Samples (23 ms bei N=2048); ohne sie blinkt alles konsistent zu spät — und zwar so konsistent, dass es wie ein Feature aussieht.
- lambda = 2 (Paper-Wert bei 200 fps) ist bei 86 fps schon 23 ms Basislinie und halbiert die Flusswerte; bei 86 fps lambda = 1 verwenden.
- JTransforms (3.2, BSD-2, 2025) lohnt nicht: 1024/2048-FFT ist kein Engpass, die Bibliothek bringt einen prozessweiten Thread-Pool mit (ConcurrencyUtils), zwei zusätzliche Artefakte in der APK, und sie kann Fenstern + Bit-Reversal + Leistungsspektrum nicht in einem Durchlauf fusionieren. Erst ab N >= 8192 oder bei DCT-Bedarf sinnvoll.
- Decoder erkennt nicht jedes Format: ALAC/APE haben keinen Plattform-Decoder -> vor createDecoderByType per MediaCodecList prüfen und mit Meldung ablehnen statt zu crashen. Opus in Ogg erst ab Android 10.
- Zwei Threads brauchen je ein eigenes Fft-Objekt (re/im/Tabellen sind Instanzfelder); geteilte FloatArrays zwischen Workern sind ein Datenrennen, das nur gelegentlich falsche Bänder liefert.
- Fortschritt als frameIndex/totalFrames melden, nicht als unbestimmten Spinner; Abbruchprüfung einmal pro 100 Frames (nicht pro Frame) und Ergebnis erst nach vollständigem Durchlauf veröffentlichen.
- 48 kHz nicht rational auf 22.05 kHz umrechnen (147:320) — entweder nativ mit N=2048 analysieren oder per Halbband ÷2 auf 24 kHz; ein naiver Linear-Interpolator ist kein Resampler und erzeugt Aliasing.

## cost

Gemessen gerechnet, nicht auf dem Gerät gemessen. Pro Sekunde Audio (44.1 kHz, N=2048, H=512, 86.1 fps): 86.1 FFTs/s, 970k Butterflies/s, 9.7 Mflop/s FFT + 0.7 M MAC/s Bandmatrix + 88k mul/s Betragsquadrat = 10.5 Mflop/s; ein Frame = 123k Flops. 4-Minuten-Song (240 s): 20672 Frames, 2.54 Gflop, PCM-Rohdaten 42.3 MB (streamen, nie halten), Ausgabe 1.32 MB Bandhüllkurven + 83 KB Fluss. Speicher Arbeitsstand < 250 KB (Ringpuffer 176 KB, re/im 16 KB, Hann/Twiddles/rev 24 KB, Bandmatrix 32 KB). Zeit auf einem 2.4-GHz-Kern mit ART-JIT (0.9–1.9 Gflop/s skalar, keine Auto-Vektorisierung): FFT+Bänder 1.3–2.8 s, Decode 0.5–1.5 s [INFERENCE], Summe 1.8–4.3 s — unter 5 s mit einem Thread, aber knapp; mit 2 Workern über 10-s-Blöcke 1.2–2.2 s. Bei 22.05 kHz/1024 halbieren sich die FFT-Flops (51.2k/Frame) und der Resampler kostet 0.34 G MAC zusätzlich (netto nur ~0.5 s Gewinn, deshalb nicht Default). Pessimistischer A53-Fall: 8–17 s -> dann parallelisieren. Vergleichswert aus der Literatur: SuperFlux-Referenz (200 fps, N=2048, 138 Filter) schafft auf einem 2.26-GHz-Core-2-Duo 60 s Audio in 2 s (30x Echtzeit) — unsere Variante hat 2.3x weniger Frames und 8x weniger Bänder, liegt also deutlich darunter.
