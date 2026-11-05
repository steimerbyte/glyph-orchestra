# Berater-Bericht: SpektralKurve2

## plan

# Umsetzungsplan: Spektralkurve → Spannung → 36 Glyph-Kanäle (Nothing Phone 3a Pro)

## 0. Anschluss an den vorhandenen Code — was übernommen wird und was neu ist

| Datei (glyphdeck) | Status | Rolle im neuen Modul |
|---|---|---|
| `glyph/NothingGlyph.kt` | **übernommen, unverändert** | Einziger Transport: bindService → `register()` → `openSession()` → `setFrameColors(int[])` → `closeSession()`/`turnOff()`. Das neue Modul spricht nur `GlyphOutput.apply()`. |
| `glyph/GlyphOutput.kt` | **übernommen** | Interface `apply(values: IntArray)`. Der Player ruft es 30×/s. `apply` kopiert intern auf `spec.channels` — der Puffer darf wiederverwendet werden. |
| `glyph/Level.kt` | **übernommen** | `MAX = 4000` (= `GlyphFrame.DEFAULT_LIGHT`), `value(percent)` (30 % → 1200). Intern wird auf 0..4095 gerechnet, die Ausgabe wird mit `Level.MAX` (4000) geklemmt. **Achtung: die Funktion heißt `value()`, nicht `raw()`.** |
| `device/Channel.kt` | **übernommen** | `Channel.index`, `DeviceSpec.all`, `Strip`, `StripShape`, `sdkCode()`. `DeviceSpec`/`Strip` liegen **in dieser Datei**, es gibt keine eigenen `DeviceSpec.kt`/`Strip.kt`. |
| `device/DeviceCatalog.kt` | **übernommen** | `PHONE_3A`: 36 Kanäle, gemessene Ringgeometrie (siehe 5.1). |
| `ui/GlyphLayout.kt` | **übernommen** | `Slot.ArcSlot.start/sweep` liefert den Mittelwinkel je Kanal — die Ringreihenfolge wird daraus berechnet, nicht neu erfunden. |
| `anim/Ticker.kt` | **übernommen** | Handler-Takt (min. 20 ms) für den 30-fps-Player. |
| `glyph/Selection.kt`, `model/PresetStore.kt` | **nicht verwendet** | Auswahl/Persistenz betreffen manuelles Setzen, nicht den Abspielpfad. |
| `model/PresetStore.kt` liegt in `model/`, nicht in `glyph/` | — | Pfadkorrektur zur Vorgabe. |

**Neu festgelegt** (fünf Dateien, alle ohne Android-Abhängigkeit außer dem Player):
1. `audio/SpectrumFeatures.kt` — Centroid, Spread, Rolloff, Flatness, ZCR, RMS.
2. `audio/Roughness.kt` — Peak-Picking + Sethares.
3. `audio/Tension.kt` — Gewichtung, EMA, Beat-Raster.
4. `audio/BandToChannel.kt` — Ringordnung, Frequenz→Kanal, dB→Helligkeit, Haltezeit/Hysterese, Stille.
5. `player/GlyphOrchesterPlayer.kt` — 30-fps-Abspieler über `GlyphOutput`.

**Die entscheidende Schnittstellenregel:** das `int[36]` ist **nach `Channel.index` indiziert**, nicht nach Ringposition. `DeviceSpec.all` liefert die Kanäle in Strip-Reihenfolge A1..A11 (20..30), B1..B5 (31..35), C1..C20 (0..19); `DeviceSpec.frame()`, `Selection.values()` und `NothingGlyph.apply()` schreiben alle `array[channel.index]`. Die Ringreihenfolge ist nur eine **Zuordnungstabelle** `ring[p] -> Channel`, geschrieben wird immer `levels[ring[p].index]`.

---

## 1. Eingang und Symbole

| Symbol | Bedeutung | Wert |
|---|---|---|
| `fs` | Abtastrate | 44100 Hz |
| `N` | Fensterlänge | 2048 |
| `H` | Hop | 512 |
| `Δf` | Binbreite | fs/N = **21,533 Hz** |
| `fps_a` | Analyse-Bildrate | fs/H = **86,133 Hz** (Δt_a = 11,61 ms) |
| `K` | positive Bins | 0..1023, genutzt 5..512 (100..11025 Hz) |
| `B` | Bänder | 16, log, 100..11025 Hz, Schritt **1,3417** = 5,09 Halbtöne |
| `P` | Kanäle | 36, Schritt **1,1395** = 2,26 Halbtöne |
| `fps_d` | Anzeige | 30 Hz, Δt_d = 33,333 ms; Verhältnis fps_a/fps_d = **2,8711** (nicht ganzzahlig!) |
| `t_b` | Beatzeiten | DoubleArray, aufsteigend, aus dem Tempomodul |

Eingang je Analyseframe n: `bandsDb[16]` (dBFS) und `P[k] = |X_k|²`. Falls das Frontend linear liefert: `E_b = 10·log10(e_b + 1e-12)`.

---

## 2. Merkmale

Alle Summen über `K = {k : 100 Hz ≤ k·Δf ≤ 11025 Hz}` (k = 5..512). `P_k` ist die Leistung, `f_k = k·Δf`.

**Spektraler Schwerpunkt (Hz)**
```
C = Σ f_k·P_k / Σ P_k
```
Leistungsgewichtet (nicht magnitude): robuster gegen Rauschen, MPEG-7-konform. Typische Pop-Mischung: 1,5–2,5 kHz im Refrain, 0,9–1,5 kHz in der Strophe, 3–5 kHz bei reinem Hi-Hat/Becken-Abschnitt. Einzelstimme ~1,2 kHz.

**Bandbreite (Hz)**
```
Bw = sqrt( Σ (f_k − C)²·P_k / Σ P_k )
```
Nur Diagnose und Plausibilitätsgate: `Bw < 150 Hz` heißt „fast reiner Ton“ → Rauheit für diesen Frame auf 0 setzen (sonst erfindet das Peak-Picking Oberschwingungen).

**Rolloff 85 % (Hz)**
```
R85 = Δf · min{ k : Σ_{j≤k} P_j ≥ 0,85·Σ_j P_j }
```
Pop: 2–4 kHz in der Strophe, 4–7 kHz im dichten Refrain, 8–10 kHz bei „air“. Wird für die Hochband-Grenze und als Dichte-Indikator benutzt.

**Flatness (Wiener-Entropie)**
```
F = exp( (1/|K|)·Σ ln(P_k + ε) ) / ( (1/|K|)·Σ P_k ),   ε = 1e-12·max(P)
```
0 = tonal, 1 = Rauschen. Pop-Mix: 0,05–0,15 (Strophe), 0,15–0,30 (Refrain mit Becken), > 0,45 nur bei Rauscheinbruch/Störung. **Nicht** als Spannungstreiber benutzen — F korreliert mit der Beckendichte, nicht mit musikalischer Spannung. Verwendung: Gate für die Rauheit (`F > 0,35` → Rauheit ist Beckenrauschen, Beitrag dämpfen).

**Zero-Crossing-Rate (Hz)**
```
ZCR = (fs / (2N)) · Σ_{n=1}^{N-1} |sgn(x_n) − sgn(x_{n−1})|
```
Bei einem Einzelton ist ZCR ≈ f; bei einem Summenmix ist ZCR ≈ C/2 bis C. **Für Pop ungeeignet als eigenständiges Merkmal** (Redundanz zu C, r > 0,9 bei breitbandigem Material) — nur als Percussion-Gate und für einen späteren Einzelspur-Modus behalten.

**RMS-Hüllkurve**
```
rms_n = sqrt( (1/N)·Σ x_n² ),  L_dB = 20·log10(rms_n) + 4,26 dB
```
Der Summand **+4,26 dB** ist Pflicht: das Hann-Fenster senkt den RMS um `sqrt(3/8) = 0,6124` (−4,26 dB). Wer ihn vergisst, hat eine um 4,26 dB zu leise Anzeige und eine falsche Stille-Schwelle. Pop-Master: −30 dBFS in der Strophe, −10 bis −8 dBFS im Refrain (integriert −14 LUFS).

**Low-/High-Grenzen für Pop (Hz)**
| Bereich | Grenzen | Inhalt |
|---|---|---|
| Low | **40–250** (durch das Frontend effektiv 100–250) | Kick-Grundton 50–80, Bass 40–200, Snare-Grundton 150–250 |
| Mid | 250–2000 | Vocals, Gitarren, Synths |
| Presence | 2000–5000 | Stimmschärfe, Snare-Teppich, Gitarren-Biss |
| High | **5000–11025** | Hi-Hats 6–12 k, Zischlaute 5–8 k, „air“ |

Die Low-Grenze 100 Hz ist eine Folge des festgelegten Frontends: die Kick bei 50–60 Hz wird nicht direkt erfasst, ihr Anschlag (100–150 Hz) schon. Das ist für eine LED-Show ausreichend, weil der Schlag ohnehin über die Flanke und nicht über den Grundton wahrgenommen wird.

---

## 3. Rauheit / Dissonanz

### 3.1 Sethares-Formel (exakt)
Für Teiltöne `(f_i, a_i)`, i = 1..P, mit `fmin = min(f_i,f_j)`, `d = |f_i − f_j|`:
```
s(f) = 0,24 / (0,0207·f + 18,96)          [1/Hz]
r_ij = a_i·a_j·( e^(−3,5·s(fmin)·d) − e^(−5,75·s(fmin)·d) )
R    = Σ_{i<j} r_ij / (Σ_i a_i)²
```
Konstanten: b1 = 3,5, b2 = 5,75, s1 = 0,0207, s2 = 18,96. Das Maximum jeder Paarung liegt bei `d* = 0,2207/s = 0,92·(0,0207·f + 18,96)` und beträgt **0,1808** (bei a_i = a_j = 1); nach der Normierung mit (Σa)² = 4 sind das 0,045 pro Paar.

Kontrollwerte (nachgerechnet):
| Paar | d | r |
|---|---|---|
| 1000/2000 Hz (Oktave) | 1000 Hz | 6,3e-10 ≈ 0 |
| 1000/1500 Hz (Quinte) | 500 Hz | 2,5e-5 ≈ 0 |
| 1000/1059 Hz (Halbton) | 59,5 Hz | **0,158** |
| 1000/1036 Hz (Maximum) | 36,5 Hz | **0,181** |
| 150/170 Hz | 20 Hz | 0,180 |

### 3.2 Warum man das NICHT auf FFT-Bins rechnen darf
Bei 1 kHz liegt das Rauheitsmaximum bei 36,5 Hz, ein Bin ist 21,5 Hz breit — benachbarte Bins liefern `r = 0,161`, also fast das Maximum, obwohl sie im Ohr zu **einem** Ton verschmelzen. Binweise Summation zählt die Energie eines Teiltons mehrfach und erzeugt ein Vielfaches der echten Rauheit. **Zwingend: Peak-Picking mit parabolischer Interpolation.**

Grenze der Auflösung: das Hann-Hauptkeulenmaß ist 4 Bins = **86 Hz**. Zwei Teiltöne, die enger liegen, verschmelzen zu einem Peak — ein Halbton-Clash bei 1 kHz (59 Hz Abstand) ist mit N = 2048 **nicht sichtbar**, ab ca. 2 kHz (118 Hz Abstand) schon. Für Pop ist das tolerierbar, weil die Rauheit dort ohnehin nur 10 % wiegt (siehe 3.4). Wer echte Rauheit im Bass/Mittelton will, rechnet einen zweiten STFT-Zweig mit N = 8192 (Δf 5,4 Hz, offline +ca. 3 s, siehe 7).

### 3.3 Peak-Picking (Pseudocode)
```
peaks = []
maxP  = max(P[k])
for k in 5..511:
    if P[k] > P[k-1] && P[k] >= P[k+1] && P[k] > maxP*10^(-45/10):
        a = 0.5*(logP[k-1]-logP[k+1]) / (logP[k-1]-2*logP[k]+logP[k+1])
        f = (k + a)*df                      // parabolisch, Fehler < 0,1 Bin
        amp = sqrt(P[k]) * (1 - 0.25*a*a)   // naeherungsweise korrigierte Amplitude
        if far enough from last (>= 4 bins) && f >= 300 Hz: peaks += (f, amp)
        if peaks.size == 40: break
R = sethares(peaks)   // 780 Paare, 2 exp je Paar
```
Parameter: max. **40** Peaks, Schwelle **−45 dB** unter dem Framemaximum, Mindestabstand **4 Bins** (Hauptkeulenbreite), `f ≥ 300 Hz` (darunter ist die Rauheitskurve mit 21,5-Hz-Bins nicht auflösbar: Maximum bei 20 Hz = 1 Bin).

### 3.4 Urteil für Pop
**Trägt nur als relative Größe, nicht als absoluter Spannungstreiber.** Begründung mit Zahlen:
- Ein gemasterter Pop-Mix hat durchgehend Verzerrung/Becken/Stimmschärfe; `R` liegt erwartungsgemäß bei 0,01–0,05 und schwankt innerhalb eines Songs um vielleicht ±30 %. Die wahrgenommene Spannung schwankt um ein Vielfaches. Die Korrelation ist schwach.
- `R` reagiert auf Beckendichte und Sättigung, nicht auf Harmonik; ein „ruhiger“ Akkordwechsel mit Streichern erzeugt mehr Rauheit als ein treibender Four-on-the-Floor.
- **Was trägt:** die *Abweichung* vom gleitenden 4-s-Mittel (`R − EMA_4s(R)`), also „es wird gerade schärfer“. Das ist ein brauchbarer Zusatz für Übergänge.
- **Gewicht 0,10**, geglättet mit τ = 400 ms, perzentilnormiert; bei `F > 0,35` (Beckenrauschen) Beitrag halbieren.

**Die 16-Band-Vereinfachung ist mathematisch tot:** benachbarte log-Bänder liegen bei 1 kHz 342 Hz auseinander; `s·d = 2,07` → `e^(−7,2) − e^(−11,9) ≈ 7,5e-4` — praktisch null. Nur die untersten Bänder (bei 150 Hz, 45 Hz Abstand) liefern 0,12. Eine „Rauheit aus Bandenergien“ ergibt für alles über 400 Hz **garantiert ~0** und misst in Wahrheit nichts. Wer rechnen will, rechnet Peaks; das ist mit 780 Paaren auch online billig (siehe 7).

---

## 4. Spannungskurve 0..1

### 4.1 Vier Zutaten, perzentilnormiert (Zwei-Pass, offline)
Jede Zutat wird über den **ganzen Song** auf P5..P95 normiert, damit die Gewichte unabhängig von Mastering und Genre wirken:
```
L̂ = clamp( (L_dB − (P95_L − 24)) / 24 , 0, 1)      // 24-dB-Fenster unter dem lautesten Fünftel
Ĉ = clamp( (log2 C − P5_logC) / (P95_logC − P5_logC) , 0, 1)   // Guard: Spanne < 0,3 Oktave -> 0,5
R̂ = clamp( (R − P5_R) / (P95_R − P5_R) , 0, 1)                // Guard: Spanne < 0,005 -> 0
N̂ = clamp( (N − P5_N) / (P95_N − P5_N) , 0, 1)                // Guard: Spanne < 0,02 -> 0
```
**Novelty N** ist *Timbre-Änderung*, nicht Onset-Flux — sonst würde jeder Kick einen Spannungspeak erzeugen:
```
u_n[b]  = E_n[b] − max_b(E_n[b])            // dB-Form, lautheitsunabhängig
N_raw   = 1 − (u_n·u_{n−1}) / (|u_n|·|u_{n−1}|)   // Kosinusdistanz, 0..1
N       = EMA(N_raw, τ = 200 ms)             // 1 − exp(-Δt/0.2) = 0,9436 pro Frame
```
Das reagiert auf Strophe→Refrain, Filteröffnung, Instrumentenwechsel — die Stellen, an denen Spannung tatsächlich springt.

### 4.2 Gewichtung
```
T_raw = 0,38·L̂ + 0,27·Ĉ + 0,10·R̂ + 0,25·N̂        (Summe 1,00)
```
Begründung der Zahlen: Lautheit ist der stärkste Einzelprädiktor (Drop/Refrain sind lauter, 24-dB-Fenster deckt die Dynamik ab); der Schwerpunkt ist der zweitstärkste (Build-ups öffnen den Filter, Hi-Hats kommen dazu) und liefert die Richtung; Novelty ist unverzichtbar, weil L̂ und Ĉ innerhalb eines Abschnitts flach sind — ohne N wäre T in jedem Refrain eine Konstante; Rauheit ist bewusst schwach (3.4).

### 4.3 Asymmetrische EMA (schnell hoch, langsam runter)
```
Δt = 1/86,133 = 11,61 ms
α_up = exp(−Δt/0,10) = 0,8904      (τ = 100 ms)
α_dn = exp(−Δt/0,40) = 0,9714      (τ = 400 ms)
T[n] = α·T[n−1] + (1−α)·T_raw[n],   α = T_raw[n] > T[n−1] ? α_up : α_dn
```
Ein Drop ist nach ~100 ms oben, ein Abfall dauert ~0,4 s — das entspricht dem Höreindruck und verhindert Flackern bei jedem Übergang.

### 4.4 Quantisierung auf das Beat-Raster
Quantum = **halbe Note** (Achtel): `q_k = t_b + k·(t_{b+1} − t_b)/2`, bei 120 BPM also 250 ms. Bei variablem Tempo wird jedes Beatintervall halbiert (kein globales BPM).
```
T_q[k] = T(t_k)                                    // Wert exakt am Rasterpunkt
für Anzeigezeit t:  k = floor((t − t0)/Δt_q)
                    T_out(t) = lerp(T_q[k], T_q[k+1], (t − t_k)/Δt_q)
```
Damit sind alle Änderungen am Beat verankert, aber die Helligkeit springt nicht (ein Sprung alle 250 ms wäre ein 4-Hz-Flackern der Gesamthelligkeit). **Kein Beat gefunden** (`t_b` leer): Raster 500 ms und `T_out = T` (keine Quantisierung).

**Vorlauf:** die Anzeige wird mit `t_out = t_audio + 30 ms` abgespielt (LEDs wirken sonst nach; 30 ms ist der übliche Ausgleichswert, per Einstellung ±200 ms nachziehbar).

Beispielwerte: (L,C,R,N) = (0,3; 0,4; 0,2; 0,1) → T = 0,267; (0,9; 0,7; 0,4; 0,8) → 0,771; (0,6; 0,6; 0,3; 0,3) → 0,495.

---

## 5. Mapping auf die 36 Kanäle

### 5.1 Ringordnung (aus der gemessenen Geometrie, nicht erfunden)
`DeviceCatalog.PHONE_3A` (Winkel Uhrzeigersinn ab 12 Uhr, wie `GlyphLayout.ArcSlot`):
| Strip | SDK-Indizes | Bogen | pro Kanal |
|---|---|---|---|
| C | 0..19 | 202° → 371° (Sweep 169°) | 8,45° |
| A | 20..30 | 40° → 130° (Sweep 90°) | 8,18° |
| B | 31..35 | 148° → 194° (Sweep 46°) | 9,20° |

Der Ring hat drei Lücken: 11°–40° (oben rechts), 130°–148° (unten rechts), 194°–202° (unten links). Die **Naht** für die Frequenzachse ist die 18°-Lücke bei 130°–148°: dort beginnt der Bass-Streifen B, und der Bass liegt damit genau unten (Mitte B bei 180°).

```kotlin
val slots = GlyphLayout(spec).slots.filterIsInstance<GlyphLayout.Slot.ArcSlot>()
val seam  = spec.strips.first { it.shape is StripShape.Arc && contains(it.shape, 180f) }.shape.start  // 148f
val ring  = slots.sortedBy { ((it.start + it.sweep / 2f - seam) + 360f) % 360f }
```
Ergebnis (Ringposition p → SDK-Index): `[31,32,33,34,35, 0..19, 20..30]`.
**Falle:** sortiert man ab 0° statt ab der Naht, zerfällt der Bass-Streifen B in zwei Enden des Arrays und dieselben tiefen Frequenzen erscheinen zweimal unten am Ring.

### 5.2 Frequenz je Ringposition
Log über den gesamten Analysebereich, 36 Kanten:
```
f_lo(p) = 100 · (11025/100)^(p/36) = 100 · 1,1395^p
f_hi(p) = 100 · (11025/100)^((p+1)/36)
f_mid(p) = sqrt(f_lo·f_hi)
```
Erste Kante 100 Hz, letzte 11025 Hz, 2,26 Halbtöne pro LED. Vollständige Tabelle (p, SDK-Index, Code, Winkel, f_lo, f_hi):

| p | idx | Code | θ° | f_lo | f_hi |
|---|---|---|---|---|---|
| 0 | 31 | B1 | 152,6 | 100 | 114 |
| 1 | 32 | B2 | 161,8 | 114 | 130 |
| 2 | 33 | B3 | 171,0 | 130 | 148 |
| 3 | 34 | B4 | 180,2 | 148 | 169 |
| 4 | 35 | B5 | 189,4 | 169 | 192 |
| 5 | 0 | C1 | 206,2 | 192 | 219 |
| 6 | 1 | C2 | 214,7 | 219 | 250 |
| 7 | 2 | C3 | 223,1 | 250 | 284 |
| 8 | 3 | C4 | 231,6 | 284 | 324 |
| 9 | 4 | C5 | 240,0 | 324 | 369 |
| 10 | 5 | C6 | 248,5 | 369 | 421 |
| 11 | 6 | C7 | 256,9 | 421 | 480 |
| 12 | 7 | C8 | 265,4 | 480 | 546 |
| 13 | 8 | C9 | 273,8 | 546 | 623 |
| 14 | 9 | C10 | 282,3 | 623 | 710 |
| 15 | 10 | C11 | 290,7 | 710 | 809 |
| 16 | 11 | C12 | 299,2 | 809 | 921 |
| 17 | 12 | C13 | 307,6 | 921 | 1050 |
| 18 | 13 | C14 | 316,1 | 1050 | 1197 |
| 19 | 14 | C15 | 324,5 | 1197 | 1364 |
| 20 | 15 | C16 | 333,0 | 1364 | 1554 |
| 21 | 16 | C17 | 341,4 | 1554 | 1771 |
| 22 | 17 | C18 | 349,9 | 1771 | 2018 |
| 23 | 18 | C19 | 358,3 | 2018 | 2299 |
| 24 | 19 | C20 | 6,8 | 2299 | 2620 |
| 25 | 20 | A1 | 44,1 | 2620 | 2986 |
| 26 | 21 | A2 | 52,3 | 2986 | 3402 |
| 27 | 22 | A3 | 60,5 | 3402 | 3877 |
| 28 | 23 | A4 | 68,6 | 3877 | 4418 |
| 29 | 24 | A5 | 76,8 | 4418 | 5035 |
| 30 | 25 | A6 | 85,0 | 5035 | 5737 |
| 31 | 26 | A7 | 93,2 | 5737 | 6538 |
| 32 | 27 | A8 | 101,4 | 6538 | 7450 |
| 33 | 28 | A9 | 109,5 | 7450 | 8490 |
| 34 | 29 | A10 | 117,7 | 8490 | 9675 |
| 35 | 30 | A11 | 125,9 | 9675 | 11025 |

Damit ist die Gruppenaufteilung durch die Geometrie vorgegeben: **B (5 LEDs, unten) = 100–192 Hz**, **C (20 LEDs, links/oben) = 192–2620 Hz**, **A (11 LEDs, rechts) = 2,6–11 kHz**.

### 5.3 Band → Kanal (lineare Interpolation in der Bandkoordinate)
Die 16 Bänder sind ebenfalls log über denselben Bereich; ihre Mittelpunktfrequenzen `fc_b` seien vom Frontend gegeben. Dann ist die Bandkoordinate eines Kanals
```
d   = (ln fc_15 − ln fc_0) / 15          // 0,2939 bei 5,09 Halbton-Schritt
v_p = (ln f_mid(p) − ln fc_0) / d
v_p = clamp(v_p, 0, 15)
b0  = min(14, floor(v_p));  w = v_p − b0
y_p = (1−w)·y[b0] + w·y[b0+1]
```
Damit teilen sich 2–3 LEDs ein Band, mit weichem Verlauf statt Treppenstufen. `v_p` läuft von −0,28 (p=0) bis 15,28 (p=35), wird also an den Enden geklemmt. (Ohne Interpolation würden 2–3 LEDs je Band exakt gleich leuchten — sichtbare Ringelung.)

### 5.4 dB → Helligkeit (Entscheidung: Gamma 1,8 + sanfte Kompression)
Pro Band, perzentilnormiert auf den Song:
```
ref_b = P95(E_b)                                   // pro Band, über alle Analyseframes
y_b   = clamp( (E_b − (ref_b − 30)) / 30 , 0, 1)   // 30 dB Anzeigefenster
Gate:  y_b = 0, wenn P95(E_b) − P10(E_b) < 6 dB    // totes Band, kein Rauschverstärker
       y_b = 0, wenn E_b < −70 dBFS
```
Vergleich der drei Varianten bei x = 0,1 / 0,3 / 0,5 / 0,7 / 0,9 (Rohwert 0..4095):
| Variante | 0,1 | 0,3 | 0,5 | 0,7 | 0,9 |
|---|---|---|---|---|---|
| linear `4095·x` | 410 | 1228 | 2048 | 2866 | 3686 |
| Gamma 1,8 `4095·x^1,8` | 65 | 469 | **1176** | 2155 | 3388 |
| Gamma 2,2 | 26 | 290 | 891 | 1868 | 3248 |

**Entscheidung: γ = 1,8.** Linear in dB ist zu flach: −24 dB (x = 0,2) ergibt 819 Rohwerte, der halbe Ring wirkt dauerhaft an. γ = 2,2 macht die unteren 60 % fast unsichtbar. 1,8 ist der Kompromiss; die 12-Bit-Auflösung wird nicht verschwendet, weil das Auge pro LED ohnehin nur ~30 Stufen unterscheidet.
**Kompression** gegen Loudness-War-Material: oberhalb x = 0,85 sanfte Kennlinie `x' = 0,85 + 0,15·tanh((x−0,85)/0,15)`; das verhindert, dass der Refrain dauerhaft bei 4095 klebt und die Dynamik innerhalb des Refrains verschwindet. (Optional, ein `tanh` je Kanal und Frame, 36·30 = 1080/s.)

**Zusammen mit der Spannung:** `v_p = round(4095·y_p^1,8)`; dann globaler Sockel für bereits leuchtende Kanäle: `if (y_p ≥ 0,02) v_p = min(4095, v_p + round(T·492))` (max. +12 %). Dunkle Kanäle bleiben dunkel, der Ring „atmet“ bei hoher Spannung.

### 5.5 Mindesthaltezeit und Hysterese
Vier Stufen, alle pro Kanal und Anzeigeframe:
1. **Schmitt-Trigger** auf `y_p`: ein bei `y ≥ 0,08` (328 Rohwerte), aus bei `y ≤ 0,04` (164). Differenz 4 % = 164 Werte — verhindert das Zappeln an der Schwelle.
2. **Mindesthaltezeit 4 Frames = 133 ms:** ein Kanal, der 0,02 überschreitet, behält seinen Wert für 4 Frames (`holdUntil[p]`, `heldPeak[p]`). Länger als 133 ms zu halten würde Transienten verschmieren; kürzer als 100 ms lässt 10-Hz-Flackern zu.
3. **Release-Rampe 45 dB/s** (1,5 normierte Einheiten/s bei 30 dB Fenster, also 0,05 pro Frame): `y_p = max(y_neu, y_alt − 0,05)`. Ein Vollausschlag fällt in ca. 610 ms auf 8 % — VU-Ballistik. Attack ist **sofort** (kein Smoothing), sonst verlieren Kicks ihre Kante.
4. **Totband 48 Rohwerte (1,2 %):** ändert sich der Zielwert um weniger als 48, bleibt der alte Wert stehen (kein 1-LSB-Glimmern).

### 5.6 Stille
```
if L_dB < −50 dBFS für ≥ 300 ms (26 Analyseframes):
    Globalstille = true; alle y = 0; T sinkt mit τ = 250 ms auf 0
    Ausblenden über 150 ms (5 Frames), dann Frames mit 0 ausgeben
if Globalstille und alle 36 Werte 0 seit ≥ 1 s: Ticker anhalten
```
Kurze Lücken (< 300 ms, z. B. zwischen Phrasen) lösen **kein** Dunkelwerden aus. Die Sitzung bleibt offen (kein `turnOff`), damit der nächste Ton ohne Neuverbindung weiterläuft. Nach der Stille rampen die Werte über 100 ms hoch, nicht sprunghaft.

---

## 6. Ausgabeformat und Abspieler

**Artefakt je Anzeigeframe m (30 fps):**
```kotlin
data class GlyphFrame(val levels: IntArray /*36, nach Channel.index*/, val tension: Float /*0..1*/)
```
Offline werden die Frames bereits **fertig gemappt** (Ringordnung, Gamma, Haltezeit, Hysterese) berechnet — der Player macht keine Mathematik mehr.
Speicher: 4-min-Song = 7200 Frames; `ShortArray(36)` (4095 passt in Short) = 72 B + 2 B Spannung → **533 KB**. Optional als Datei `GO1` (Magic, Version, fps, frameCount, bpm) cachen.

**Player (30 fps, ein Frame Rückstand):**
```kotlin
val buf = IntArray(36)
fun onTick(audioPosUs: Long) {
    val m = ((audioPosUs + 30_000L) * 30 / 1_000_000L).toInt().coerceIn(0, frames.size - 1)
    val f = frames[m]
    for (i in 0 until 36) buf[i] = f.levels[i].toInt()
    output.apply(buf)          // NothingGlyph kopiert intern; Puffer darf bleiben
    tension = f.tension
}
```
`Ticker.start(33)` (Handler, min. 20 ms) oder `Choreographer`. Seek = nur `m` neu berechnen; kein Zustand nötig, weil Haltezeit/Hysterese schon offline eingerechnet sind.

---

## 7. Rechenbudget

**Offline (einmal je Song, 4 min = 240 s):**
| Posten | Menge | Zeit (1 Kern, Kotlin/JIT) |
|---|---|---|
| Analyseframes | 240·86,133 = **20.672** | — |
| 2048-Punkt-Real-FFT | 20.672 × (5·1024·10 ≈ 51 k Flops) ≈ 1,1 GFlop | 0,6–1,2 s (30–60 µs/Frame) |
| Bandenergien | 20.672 × 1024 Mult-Add | 0,05 s |
| Peak-Picking | 20.672 × ~2 k Op | 0,2 s |
| Rauheit | 20.672 × 780 Paare × 2 exp = **32 M exp** | 0,6 s (mit LUT über s·d: 0,15 s) |
| Merkmale/Tension | 20.672 × ~200 Op | < 0,1 s |
| Zweiter Pass (Perzentile, 30-fps-Mapping, Hysterese) | 20.672 + 7200 | < 0,1 s |
| **Summe** | | **~2–4 s**, mit 4 Threads (Frames sind unabhängig, chunkweise) **~1 s** |
| Speicher Pass 1 | 20.672 × ~90 B | ~2 MB |
| Optionaler 8192er-FFT-Zweig für Rauheit | 4× FFT-Kosten | +2–4 s |

Der Audio-Dekode (MediaExtractor/MediaCodec) kommt dazu: ~1 s für 4 min. **Gesamt unter 5 s**, also problemlos vor dem Start.

**Zur Laufzeit (30 fps):**
| Posten | pro Frame | pro Sekunde |
|---|---|---|
| Index + 36 Kopien | ~40 ns | 1,2 µs |
| `setFrameColors` (Binder + SDK) | 0,1–0,5 ms | 3–15 ms |
| **CPU** | **< 0,5 ms** | **< 1,5 % eines Kerns** |

Es bleibt **keine** Analysearbeit pro Frame: keine FFT, keine exp, keine Sortierung. Der einzige variable Posten ist der Binder-IPC, und der ist durch die 30-Hz-Taktung gedeckelt.

---

## 8. Pseudocode (Gesamtfluss)

```kotlin
// ---------- Pass 1: pro Analyseframe ----------
for (n in 0 until nFrames) {
    val x = pcm[n*H, n*H + N]                       // FloatArray, mono
    val rmsDb = 20*log10(sqrt(x.sumOf{it*it}/N)) + 4.26f
    val zcr   = crossings(x) * fs / (2f*N)
    hann(x); fftReal(x, re, im)
    P[k] = re[k]*re[k] + im[k]*im[k]                // k = 5..512
    centroid = sum(f*P)/sum(P); spread = sqrt(sum((f-centroid)^2*P)/sum(P))
    rolloff  = rolloff85(P); flat = flatness(P)
    peaks    = pickPeaks(P, max=40, relDb=-45f, minBins=4, fMin=300f)
    rough    = sethares(peaks)                      // 780 Paare
    nov      = 1 - cos(u, uPrev)                    // u = bandsDb - max(bandsDb)
    store(RawFrame(rmsDb, centroid, spread, rolloff, flat, zcr, bandsDb.copyOf(), rough, nov))
}
// ---------- Pass 2: Perzentile ueber den ganzen Song ----------
p95Bands[16]; p5/p95CentroidLog; p5/p95Rough; p5/p95Nov; p95Rms
// ---------- Pass 3: 30-fps-Raster ----------
for (m in 0 until duration*30) {
    val t = m/30.0; val n = t*86.1328125
    val e = lerpBandsDb(n)                          // linear in dB
    val T = tensionAt(t)                            // EMA + Beat-Raster, Abschnitt 4
    val y = FloatArray(36)
    for (p in 0 until 36) {
        val v = bandCoord(p)                        // 5.3
        val b0 = min(14, floor(v).toInt()); val w = v - b0
        val yb = lerp(yBand[b0], yBand[b0+1], w)
        var out = 4095f * yb.pow(1.8f)
        if (yb >= 0.02f) out = min(4095f, out + T*492f)
        y[p] = out
    }
    val levels = holdHysteresis(y, m)                // 5.5, zustandsbehaftet
    frames += GlyphFrame(levels, T)
}
```

---

## 9. Parameterliste (alle Zahlen an einer Stelle)

| Parameter | Wert |
|---|---|
| Analyse | N 2048, Hop 512, 86,133 fps, Δf 21,533 Hz |
| Bänder | 16, 100–11025 Hz, Schritt 1,3417 (5,09 Halbtöne) |
| Kanäle | 36, Schritt 1,1395 (2,26 Halbtöne), Naht 148° |
| Peaks | max. 40, −45 dB, 4 Bins Abstand, f ≥ 300 Hz |
| Rauheit | b1 3,5; b2 5,75; s1 0,0207; s2 18,96; Normierung (Σa)² |
| Anzeigefenster | 30 dB, ref_b = P95(E_b), totes Band bei < 6 dB Spanne |
| Gamma | 1,8; Kompression ab x 0,85 (tanh, 15 % Rest) |
| Schmitt | an 0,08 / aus 0,04 (328/164) |
| Haltezeit | 4 Frames = 133 ms |
| Release | 45 dB/s = 0,05 je Frame |
| Totband | 48 Rohwerte |
| Stille | −50 dBFS, 300 ms, Fade 150 ms |
| Tension | 0,38 / 0,27 / 0,10 / 0,25 |
| EMA | τ_up 100 ms (α 0,8904), τ_dn 400 ms (α 0,9714) |
| Raster | halber Beat (120 BPM: 250 ms), linear interpoliert |
| Vorlauf | 30 ms |
| Ausgabe | 30 fps, int[36] 0..4095, geklemmt auf Level.MAX 4000 |

---

## 10. Fallen

1. **Index vs. Ringposition.** `levels[ring[p].index]`, nie `levels[p]`. `spec.all` ist A,B,C — nicht die Ringreihenfolge.
2. **Naht bei 148°.** Sortieren ab 0° zerreißt den Bass-Streifen (5.1).
3. **Hann-RMS −4,26 dB** und FFT-Normierung: ein Vollausschlag-Sinus hat im Betragsspektrum `N/4 = 512`. Ohne diese Konstante sind alle dB-Werte verschoben und die Stille-Schwelle greift falsch.
4. **Rauheit auf Bins ist falsch** (3.2) — Peak-Picking ist Pflicht; Band-Variante ist bei Pop ~0 (3.4).
5. **Unter 2 kHz keine Halbton-Rauheit** mit N = 2048 (Hauptkeule 86 Hz). Entweder akzeptieren (Gewicht 0,10) oder 8192er-Zweig.
6. **ZCR ist redundant** zum Schwerpunkt bei Summenmaterial — nicht als eigenes Spannungsmerkmal verkaufen.
7. **Per-Band-P95** verstärkt tote Bänder zu Rauschen → Gate bei < 6 dB Spanne.
8. **Haltezeit ohne Release-Rate** ergibt 8-Hz-Flackern; beides zusammen mit Totband.
9. **Beat-Raster nicht als Treppe** ausgeben (4 Hz Globalflackern) — zwischen den Rasterpunkten interpolieren.
10. **4095 vs. 4000:** SDK-Vorgabe ist 4000 (`Level.MAX`); 4095 ist die 12-Bit-Grenze, aber nicht belegt. Ausgabe mit `Level.MAX` klemmen.
11. **`NothingGlyph.apply` kopiert** und verwirft Frames vor `openSession` in `pending` — der Player darf erst nach READY senden.
12. **2,8711 ist nicht ganzzahlig** — zeitbasierte Indizierung, keine Integer-Dekimation.
13. **Perzentile brauchen zwei Pässe** — nicht streamend normalisieren.
14. **`GlyphLayout` liefert den Mittelwinkel** (`start + sweep/2`) — nicht neu rechnen.
15. **Leere Beatliste** (kein Tempo erkannt) → 500-ms-Raster, keine Division durch 0.


## formulas

- Centroid: C = Σ f_k·P_k / Σ P_k, f_k = k·Δf, P_k = |X_k|², k über 100..11025 Hz
- Spread: Bw = sqrt( Σ (f_k−C)²·P_k / Σ P_k )
- Rolloff 85 %: R85 = Δf·min{k : Σ_{j≤k} P_j ≥ 0,85·Σ_j P_j}
- Flatness: F = exp((1/|K|)Σ ln(P_k+ε)) / ((1/|K|)Σ P_k), ε = 1e-12·max(P)
- ZCR: ZCR = fs/(2N)·Σ|sgn(x_n)−sgn(x_{n−1})|
- RMS: rms = sqrt((1/N)Σx_n²); L_dB = 20·log10(rms) + 4,26 dB (Hann-Korrektur)
- Sethares: s(f) = 0,24/(0,0207f+18,96); r_ij = a_i a_j (e^(−3,5·s·d) − e^(−5,75·s·d)); R = Σ_{i<j} r_ij / (Σa_i)²
- Rauheitsmaximum: d* = 0,2207/s = 0,92·(0,0207f+18,96); r_max = 0,1808 je Paar (a=1)
- Novelty: u = bandsDb − max(bandsDb); N_raw = 1 − cos(u, u_prev); N = EMA(N_raw, 200 ms)
- Tension: T_raw = 0,38·L̂ + 0,27·Ĉ + 0,10·R̂ + 0,25·N̂; asymmetrische EMA α_up = exp(−Δt/0,10) = 0,8904, α_dn = exp(−Δt/0,40) = 0,9714
- Beat-Raster: q_k = t_b + k·(t_{b+1}−t_b)/2; T_out = lerp(T_q[k], T_q[k+1], (t−t_k)/Δt_q)
- Frequenz je Ringposition: f_lo(p) = 100·(11025/100)^(p/36) = 100·1,1395^p
- Bandkoordinate: d = (ln fc_15 − ln fc_0)/15; v_p = (ln f_mid(p) − ln fc_0)/d; b0 = floor(v), y = (1−w)y[b0] + w·y[b0+1]
- Helligkeit: y_b = clamp((E_b − (P95(E_b) − 30))/30, 0, 1); v = round(4095·y^1,8)
- Spannungssockel: if y ≥ 0,02 then v = min(4095, v + round(T·492))
- Release: y = max(y_neu, y_alt − 0,05) pro Anzeigeframe (45 dB/s)

## parameters

- Analyse: fs 44100, N 2048, Hop 512 → Δf 21,533 Hz, 86,133 fps, 1024 Bins (genutzt 5..512)
- 16 Bänder 100–11025 Hz, Schritt 1,3417 (5,09 Halbtöne), Mittelpunkte 115,9 … 9518 Hz
- 36 Kanäle, Schritt 1,1395 (2,26 Halbtöne); Naht bei 148°, Bass-Streifen B (5 LEDs) unten
- Peak-Picking: max. 40 Peaks, Schwelle −45 dB unter Framemaximum, Mindestabstand 4 Bins, nur f ≥ 300 Hz
- Sethares-Konstanten: b1 3,5; b2 5,75; s1 0,0207; s2 18,96; Normierung auf (Σa)²
- Rauheit: Gewicht 0,10; EMA τ 400 ms; bei Flatness > 0,35 halbiert; Guard: P95−P5 < 0,005 → 0
- Anzeigefenster D = 30 dB; Referenz ref_b = P95(E_b) je Band; totes Band bei P95−P10 < 6 dB
- Gamma 1,8; optionale Kompression oberhalb x = 0,85 mit 15 % Restweg (tanh)
- Schmitt-Trigger: an bei 0,08 (328), aus bei 0,04 (164); Totband 48 Rohwerte
- Mindesthaltezeit 4 Frames = 133 ms; Release 45 dB/s = 0,05 normierte Einheiten je Frame; Attack sofort
- Stille: L_dB < −50 dBFS für 300 ms; Fade 150 ms; Ticker-Stopp nach 1 s Null
- Tension-Gewichte: Lautheit 0,38, Centroid 0,27, Rauheit 0,10, Novelty 0,25
- Tension-Normierung: L̂ 24-dB-Fenster unter P95; Ĉ/R̂/N̂ per P5..P95, Guards 0,3 Oktave / 0,005 / 0,02
- EMA: τ_up 100 ms (α 0,8904), τ_dn 400 ms (α 0,9714) bei 86,133 fps
- Beat-Raster: halber Beat; 120 BPM → 250 ms, 4 min → 960 Quanta; lineare Interpolation zwischen Rasterpunkten
- Anzeige-Vorlauf 30 ms (einstellbar ±200 ms)
- Ausgabe: 30 fps, int[36] nach Channel.index, 0..4095 intern, Ausgabe auf Level.MAX = 4000 geklemmt
- Speicher: ShortArray(36) + Short-Spannung je Frame = 74 B; 4 min = 7200 Frames = 533 KB

## pitfalls

- int[36] ist nach Channel.index indiziert (A1..A11 = 20..30, B1..B5 = 31..35, C1..C20 = 0..19), nicht nach Ringposition; DeviceSpec.all ist A,B,C.
- Ringordnung ab 0° sortiert zerreißt den Bass-Streifen B in zwei Arrayenden — Naht ist 148° (Start des Streifens, der 180° enthält).
- Hann-Fenster senkt RMS um 4,26 dB (Faktor 0,6124) — ohne Korrektur sind alle Pegel und die Stille-Schwelle falsch.
- Rauheit über FFT-Bins zählt Teiltöne mehrfach (bei 1 kHz liefern benachbarte 21,5-Hz-Bins r = 0,161 statt 0) — Peak-Picking ist Pflicht.
- Rauheit aus den 16 Bandenergien ist bei Pop praktisch null: 5,09 Halbtöne Abstand ergeben bei 1 kHz s·d = 2,07 → r ≈ 7,5e-4.
- Mit N = 2048 (Hann-Hauptkeule 86 Hz) sind Halbton-Clashes unter ~2 kHz unsichtbar; unter 300 Hz ist die Rauheitskurve (Maximum 20 Hz) nicht auflösbar.
- ZCR ist bei Summenmaterial redundant zum Spektralen Schwerpunkt und taugt nicht als eigenes Spannungsmerkmal.
- Per-Band-P95-Normierung verstärkt tote Bänder (kein Höhenanteil) zu Rauschen — Gate bei Spanne < 6 dB und E_b < −70 dBFS.
- Haltezeit ohne Release-Rate erzeugt 8-Hz-Flackern; Totband 48 Rohwerte gegen 1-LSB-Glimmern.
- Beat-Raster als Treppe ausgeben erzeugt 4-Hz-Globalflackern — zwischen den Rasterpunkten linear interpolieren.
- SDK-Obergrenze: Level.MAX = 4000 (DEFAULT_LIGHT), 4095 ist die 12-Bit-Grenze; Ausgabe auf 4000 klemmen. Die Funktion heißt Level.value(), nicht raw().
- NothingGlyph.apply kopiert das Array und merkt Frames vor openSession nur in pending — erst nach READY senden.
- 86,133/30 = 2,8711 ist nicht ganzzahlig — zeitbasiert indizieren, nicht dezimieren.
- Perzentil-Normierung braucht zwei Pässe über den ganzen Song; streamend ändert sich die Helligkeit rückwirkend.
- GlyphLayout.ArcSlot liefert start+sweep/2 als Mittelwinkel — nicht neu rechnen, sonst driften Karte und Ausgabe auseinander.
- Leere Beatliste (kein Tempo erkannt) muss auf ein 500-ms-Raster fallen, sonst Division durch 0.
- DeviceSpec und Strip liegen in device/Channel.kt (keine eigenen Dateien); PresetStore liegt in model/, nicht in glyph/.

## cost

Offline je 4-min-Song (240 s): 20.672 Analyseframes; 2048-Punkt-Real-FFT ≈ 1,1 GFlop ≈ 0,6–1,2 s auf einem Kern (30–60 µs/Frame, Kotlin/JIT, ohne JNI); Bandenergien 0,05 s; Peak-Picking 0,2 s; Rauheit 32 M exp ≈ 0,6 s (mit exp-LUT 0,15 s); Merkmale/Tension < 0,2 s; zweiter Pass (Perzentile, 30-fps-Mapping, Hysterese) < 0,1 s. Summe 2–4 s auf einem Kern, mit 4 Threads (Frames unabhängig) ≈ 1 s; plus Audio-Dekode ~1 s. Speicher Pass 1 ≈ 2 MB, Endartefakt 533 KB (7200 Frames × 74 B). Zur Laufzeit bei 30 fps bleibt nur Indexrechnung + 36 Kopien (~40 ns) + ein setFrameColors-Binderaufruf (0,1–0,5 ms) → unter 0,5 ms je Frame, < 1,5 % eines Kerns; keine FFT, kein exp, keine Sortierung im Abspielpfad.
