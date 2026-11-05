# Berater-Bericht: TempoBeat2

## plan

# Umsetzungsplan: Tempo, Takt, Beat-Tracking (Glyph Orchester)

## 0. Zeitbasis und Symbole

- f_s = 44100 Hz, N = 2048, H = 512 → **f_f = f_s/H = 86,1328 Frames/s**, Frameabstand **Δt = 11,610 ms**
- Binbreite Δf = f_s/N = **21,533 Hz**; Bin k ↔ k·21,533 Hz. Bins 1..512 = 21,5 Hz … 11 025 Hz. Die 16 Bänder liegen bei 100/134,2/180/241,5/324/434,8/583,3/782,6/1050/1408,8/1890/2535,9/3402,4/4564,9/6124,6/8217/11025 Hz.
- T = 240 s · 86,1328 = **20 672 Frames** für 4 Minuten.
- **Framezeit ist die Fenstermitte**: t_c(i) = (i·H + N/2)/f_s. Wer i·H/f_s nimmt, verschiebt alle Beats um N/(2f_s) = **23,2 ms**. Der verbleibende konstante Restversatz (Bruchteil eines Hops) ist für alle Beats gleich und wird **einmalig** mit einem Klicktrack gemessen, nicht hergeleitet — er beeinflusst weder Tempo noch Takt.
- Beat-Zeit in Sekunden: t_i = (i·H + N/2)/f_s + t_kalib, gerundet auf 1 ms.

Wichtig für die Ausgabe: der Ticker der bestehenden App hat `MIN_INTERVAL_MS = 20L`. Damit ist der Glyph-Rahmen ohnehin auf **20 ms** quantisiert — Beat-Zeitgenauigkeit unter 20 ms ist für die Anzeige wertlos (wohl aber für die Periodenschätzung, die über viele Beats mittelt). Ziel: Rasterfehler ≤ 20 ms, das ist Faktor 2,3 unter der menschlichen Streuung von 46,5 ms (Ellis 2007, gemessene Standardabweichung zwischen Annotationen).

---

## 1. Onset-Hüllkurve

### 1.1 Formel

Spektrum |X(n,k)| (Betrag, aus der vorhandenen FFT), skaliert so, dass ein Vollaussteuerungssinus |X| = 1 ergibt (also durch N geteilt).

1. **Log-Kompression**: L(n,k) = ln(1 + λ·|X(n,k)|), **λ = 1000**.
   Wirkung mit Zahlen: −60 dBFS (|X| = 10⁻³) → L = 0,693; 0 dBFS (|X| = 1) → L = 6,909. Der Eingangsdynamikbereich 1000:1 wird zu 10:1. Genau das meint „logarithmische Kompression": leise Shaker und lauter Kick tragen ähnlich viel bei, ohne dass die leisen ganz verschwinden.
   λ-Begründung: Böck & Widmer (DAFx-13, Gl. 4) schreiben log₁₀(|X|·F + 1) auf **unnormierte** DFT-Beträge (dort erreicht |X| ≈ N/2 = 1024). log(1 + |X|_unnorm) ist mit |X|_norm = |X|_unnorm/1024 identisch zu log(1 + 1024·|X|_norm). λ = 1000 ist dieselbe Kurve, auf unsere Normierung umgerechnet.

2. **SuperFlux-Differenz mit Max-Filter über 3 Bins** (Halbwellengleichrichtung implizit durch max(0,·)):
   D(n,k) = L(n,k) − max{ L(n−Δ, k−1), L(n−Δ, k), L(n−Δ, k+1) }
   O_raw(n) = Σ_{k=1}^{512} max(0, D(n,k))

   **Δ = 1**: bei H = 512 ist der effektive Zeitabstand 11,6 ms. Das Paper nutzt Δ = 2 bei 200 fps, also 2·5 ms = 10 ms. Gleicher effektiver Abstand, deshalb Δ = 1 und nicht 2 — bei Δ = 2 wäre der Abstand 23 ms und die Onset-Zeit verschmiert.
   **Max-Filter 3 Bins**: ±1 Bin = ±21,5 Hz. Das ist die Breite eines Vierteltons nur bei ~2 kHz; bei 100 Hz sind es ±3,7 Halbtöne, bei 5 kHz ±0,07. Für Perkussion ist das irrelevant (kein Vibrato), es wirkt als Toleranz gegen Stimmungsschwankungen und Hann-Leakage. Wer das Paper exakt nachbilden will, macht die Breite frequenzabhängig: m(k) = max(1, round(0,0293·k)) mit 2^{1/24}−1 = 2,93 % (Halbbreite eines Vierteltons) → k=100 ⇒ 3 Bins, k=500 ⇒ 15 Bins. Nicht nötig; dokumentiert, damit es niemand für einen Fehler hält.

3. **Normalisierung** (drei getrennte Schritte, zwei verschiedene Ausgaben!):
   a) lokalen Mittelwert entfernen: μ(n) = (1/W)·Σ_{j=n−W/2}^{n+W/2−1} O_raw(j), **W = 1,5 s = 129 Frames**, gleitende Summe (O(1) je Frame). Entspricht ~0,67 Hz Hochpass (Ellis: 0,4 Hz) und nimmt Strophe/Refrain-Lautstärkesprünge heraus.
   b) O_hp(n) = max(0, O_raw(n) − μ(n))
   c) Skalierung: σ = StdAbw(O_hp) über den ganzen Track; **O_dp = O_hp/σ, nicht negativ** (für den DP); **Õ_acf = (O_dp − mean(O_dp))/σ** (für die ACF).

   Das ist ein echter Fallstrick: die ACF braucht Nullmittel (sonst dominiert μ²·(T−τ) und verzerrt kurze Lags nach oben), der DP darf **nicht** nullmittig sein — bei nullmittigem O belohnt jeder weggelassene Beat den Score um −Ō, der DP würde Beats systematisch ausdünnen. Zwei Envelopes aus einer Quelle.

4. **Keine zusätzliche Glättung.** Das 46,4-ms-Hann-Fenster ist die Glättung (Ellis' 32-ms-Analyse plus 20-ms-Gauß), zusätzliches Filtern kostet nur Zeitauflösung.

### 1.2 Warum das für Perkussion besser ist als Mel-Bänder

1. **Bandkollision, mit Zahl**: die 16 Bänder beginnen bei 100 Hz, Band 1 = 100–134 Hz. Die Kick-Fundamentale liegt bei 50–90 Hz (EDM typisch 55–65) und damit **komplett unterhalb** des Bandrasters. Im Band-Flux ist der stärkste Beat-Hinweis des Genres unsichtbar; per Bin liegt er in k = 3–4. Genau das ist der Grund, hier nicht auf die 16 Bänder zu gehen.
2. **Maskierung**: dB je Band ist ein Verhältnis. Ein lautes gehaltenes Pad in Band 5 hebt den Bandpegel; ein leiser Hi-Hat-Onset im selben Band liefert dann nur wenige dB Differenz und verschwindet. Per Bin liegen Pad (200 Hz, k≈9) und Hi-Hat (8 kHz, k≈371) in getrennten Bins — keine Maskierung.
3. **Kanaldichte**: 512 unabhängige Terme statt 16. Ein schmalbandiges Ereignis trägt 1/16 der Bandenergie, aber seinen vollen Bin-Beitrag → höherer Störabstand je Ereignis.
4. **Zeitauflösung**: Band-Pipelines glätten typisch 20 ms und arbeiten mit 32–46-ms-Analyse (Ellis). Wir glätten nicht, Frameabstand 11,6 ms.
5. **Ehrliche Gegenrechnung**: Ellis' Beat-Tracker benutzt selbst 40 Mel-Bänder (dB, HWR, Summe) und erreichte 58,8 % auf MIREX-06. Mel ist für **Periodenschätzung** also nicht falsch; der Unterschied liegt in der Peakschärfe, und die zählt hier, weil an jedem Beat ein Glyph-Blitz hängt. Die Gemeinsamkeiten (log-Kompression, HWR, Summe, Normierung) sind wichtiger als das Bandraster — wer die 16 Bänder behalten will, verliert Tempo-Genauigkeit, nicht Tempo-Richtigkeit.

---

## 2. Tempogramm

### 2.1 Autokorrelation

r(τ) = 1/(T−τ) · Σ_{t=0}^{T−τ−1} Õ(t)·Õ(t+τ),  τ = 1 … 260 Frames

260, weil der Kamm bei 60 BPM die dritte Harmonische bei 3·86,13 = 258 braucht. (Nicht 344: mehr als 3 Harmonische bringen bei 0,25-BPM-Raster nichts und kosten nur.)

**Bias-Korrektur** (Division durch T−τ, erwartungstreuer Schätzer). Zahlen:
- global, T = 20 672: Korrektur bei τ = 86 ist **0,42 %** → praktisch bedeutungslos.
- Tempo-Kurve mit W = 8 s (689 Frames): **12,5 %** → ohne Korrektur 12 % zu schnell.
- W = 4 s (344 Frames): **25 %** → unbrauchbar ohne Korrektur.

Die eigentlich richtige Korrektur für die **Peakauswahl** ist die Signifikanz, nicht die Amplitude: unter der Nullhypothese (weißes Rauschen, Einheitsvarianz) gilt E[r] = 0 und Var[r] ≈ 1/(T−τ), also ist z(τ) = r(τ)·√(T−τ) über alle Lags vergleichbar. Wir behalten r für die Kamm-Summe (Amplituden müssen addierbar bleiben) und nutzen z nur für Schwellen.

### 2.2 Lagrange-Interpolation (3-Punkt, parabolisch)

Stützstellen k−1, k, k+1; δ ∈ [0,1):

p_k(δ) = r(k−1)·δ(δ−1)/2 + r(k)·(1−δ²) + r(k+1)·δ(δ+1)/2

Maximum: **δ\* = 0,5·(r(k−1) − r(k+1)) / (r(k−1) − 2r(k) + r(k+1))**
Wert:    **r(k+δ\*) = r(k) − (r(k+1) − r(k−1))² / (8·(r(k+1) − 2r(k) + r(k−1)))**
Guard: |Nenner| < 1e-6 → δ\* = 0.

Doppelnutzung: (i) Resampling der ACF auf fraktionale Lags d·τ für den Kamm, (ii) Verfeinerung des Maximums → sub-Frame-BPM.

**Warum das Pflicht ist, nicht Kür**: 1 Frame = 11,61 ms. Bei 120 BPM ist das 2,32 % der Periode, bei 180 BPM 3,48 %, bei 60 BPM 1,16 %. Ohne Interpolation ist die BPM-Auflösung ±2–3 % (bei 120: ±3 BPM) — das sind über 30 Takte 90 BPM·2 %·120 s ≈ 5 s Drift, hörbar und sichtbar.

### 2.3 Kammfilterbank — ohne Filterbank

Herleitung statt Implementierung. Für einen Kamm mit M Taps im Abstand τ:

E(τ) = Σ_t (Σ_{m=0}^{M−1} O(t−mτ))² = M·r(0) + 2·Σ_{d=1}^{M−1} (M−d)·r(dτ)

Die Kammfilter-Energie **ist** eine dreieckig gewichtete Summe der ACF bei Vielfachen von τ. Also eine ACF rechnen, dann:

**S(τ) = Σ_{d=1}^{3} (4−d)·r(dτ) = 3r(τ) + 2r(2τ) + r(3τ)**  (M = 4)

Das ist Ellis' TPS2/TPS3 (Gln. 7/8) in allgemeiner Form; seine 0,5/0,25-Gewichte sind die auf M = 2 bzw. 3 abgeschnittene, handgetunte Variante. Kein Resonator, kein Filter je Kandidat — Kosten: eine ACF plus 481·3 Interpolationen.

**Perzeptuelle Gewichtung** (Gl. 6 bei Ellis):

W(bpm) = exp(−½·(log₂(bpm/120)/σ_τ)²), **σ_τ = 0,9 Oktaven**
S̃(bpm) = W(bpm)·S(τ(bpm)),  τ(bpm) = 60·f_f/bpm

σ = 0,9 ist Ellis' getunter Wert für die TPS2/TPS3-Kombination; 1,4 Okt. gilt nur für die reine TPS. Zahlen: W(60) = 0,539, W(90) = 0,899, W(120) = 1,000, W(150) = 0,938, W(180) = 0,810. Der 60-BPM-Kandidat muss also 1,85× so viel Roh-Score haben wie 120, um zu gewinnen — das ist die gewünschte Faltung von Halbtempo.

Raster: 60…180 in 0,25 BPM (481 Werte), danach Lagrange-Verfeinerung → ~0,02 BPM. Kosten < 0,1 ms.

**Fensterlänge**: global T (ganzer Track) für das Haupttempo — bestes Signal-Rausch-Verhältnis, Bias 0,42 %. Für die Tempo-Kurve (nur bei IBI-Streuung σ/μ > 2 % nötig): W = 8 s (689 Frames), Hop 1 s. 8 s enthalten bei 60 BPM acht Perioden → stabil; 4 s nur vier und 25 % Bias.

### 2.4 DP-Beat-Tracking nach Ellis (2007)

**Zustand**: nur t (Frame-Index). τ ist **Entscheidungsvariable**, keine Zustandsdimension. Eine 2-D-Zustands-DP C(t,τ) wäre nötig, wenn das Tempo selbst mitverfolgt werden soll (Dixon 2001) — kostet Faktor #τ und ist für importierte Songs unnötig.

Kostenfunktion (Gl. 1/2):  C({t_i}) = Σ_i O(t_i) + α·Σ_{i≥2} F(t_i − t_{i−1}, τ_p)
Übergangsstrafe (Gl. 2):   **F(Δt, τ_p) = −(ln(Δt/τ_p))²**  (Maximum 0 bei Δt = τ_p, symmetrisch auf der Log-Achse)
Rekursion (Gl. 3):         **C\*(t) = O(t) + max_{p ∈ [τ_p/2, 2τ_p]} { α·F(p, τ_p) + C\*(t−p) }**
Backlink (Gl. 4):          **P\*(t) = argmax_{p} { α·F(p, τ_p) + C\*(t−p) }**

Suchbereich p = round(τ_p/2) … round(2τ_p) (Ellis §2): bei 120 BPM 21…86 → 66 Kandidaten je Frame.

**α hergeleitet, nicht abgeschrieben.** F(1±ε) = −(ln(1±ε))². Mit O auf σ = 1:
- ±5 % → −0,00238·α
- ±10 % → −0,00908·α
- ±20 % → −0,03324·α
- Bereichsgrenze (Δt = 2τ) → −0,4805·α

Ein typischer Onset-Peak ist 2–3 σ. Damit die DP ±10 % duldet, aber ±20 % verbietet: α·0,00908 ≈ 3 σ → **α = 330…400**. Mit **α = 400**: ±10 % kostet 3,63 σ (wird geschluckt), ±20 % kostet 13,3 σ (verboten), die Bereichsgrenze 192 σ (nie erreicht). Ellis' Optimum 680 liegt im flachen Bereich (100…2000), seine Aussage „trackt Tempoänderungen bis ±10 %" passt zu dieser Herleitung.

**Ende** (Gl. 3): t_N = argmax_{t ∈ [T−2τ_p, T]} C\*(t); dann Backtrace über P\*, bis P\* < 0 (bei uns: −1).

**Zwei-Pass für Tempoänderungen** (Ellis §5.1, Optimalität geht verloren, funktioniert für Drift):
1. globales τ_p → Beats
2. τ_loc(t) = Median der IBI über die 8 Beats um t
3. DP erneut mit F(p) = −(ln(p/τ_loc(t−p)))²

Pass 2 nur, wenn σ_IBI/μ_IBI > 2 % — bei konstantem Tempo ist ein starres Raster der bessere Output (siehe §5, Zappeln).

### 2.5 Pseudocode

```kotlin
// ---------- Tempogramm ----------
const val FPS = 44100f / 512f            // 86,1328
const val SIGMA_OCT = 0.9f               // Ellis
const val M = 4                          // Kamm-Taps

fun acf(o: FloatArray, kmax: Int): FloatArray {
    val n = o.size
    val r = FloatArray(kmax + 2)
    for (k in 1..kmax) {
        var s = 0f
        for (t in 0 until n - k) s += o[t] * o[t + k]
        r[k] = s / (n - k)               // erwartungstreu (T-tau)
    }
    return r
}

// Lagrange-3 an fraktionalem Lag x
fun acfAt(r: FloatArray, x: Float): Float {
    val k = x.toInt(); val d = x - k
    if (k < 1 || k + 1 >= r.size) return 0f
    val a = r[k - 1]; val b = r[k]; val c = r[k + 1]
    return 0.5f * a * d * (d - 1f) + b * (1f - d * d) + 0.5f * c * d * (d + 1f)
}

fun tempogram(onset: FloatArray): Tempogram {
    // 1) Nullmittel NUR fuer die ACF (DP-Envelope bleibt nicht-negativ)
    val mu = onset.average().toFloat()
    val sd = sqrt(onset.fold(0f) { s, v -> s + (v - mu) * (v - mu) } / onset.size)
    val o = FloatArray(onset.size) { (onset[it] - mu) / sd }

    val kmax = (M * 60f * FPS / 60f).toInt() + 2        // 258 + 2 = 260
    val r = acf(o, kmax)

    // 2) Kamm + Prior auf feinem Raster
    val nB = 481                                        // 60..180, Schritt 0,25
    val sc = FloatArray(nB)
    for (i in 0 until nB) {
        val bpm = 60f + 0.25f * i
        val tau = 60f * FPS / bpm
        var s = 0f
        for (d in 1..M - 1) s += (M - d) * acfAt(r, d * tau)     // 3,2,1
        val l2 = log2(bpm / 120f)
        sc[i] = s * exp(-0.5f * (l2 / SIGMA_OCT) * (l2 / SIGMA_OCT))
    }

    // 3) Peak + Lagrange-Verfeinerung
    var bi = 1
    for (i in 1 until nB - 1) if (sc[i] > sc[bi]) bi = i
    val den = sc[bi - 1] - 2 * sc[bi] + sc[bi + 1]
    val dlt = if (abs(den) < 1e-9f) 0f else 0.5f * (sc[bi - 1] - sc[bi + 1]) / den
    val bpm = 60f + 0.25f * (bi + dlt)
    return Tempogram(bpm, sc, bi)
}
```

```kotlin
// ---------- DP-Beat-Tracking ----------
fun beatTrack(onset: FloatArray, period: Float, alpha: Float = 400f): IntArray {
    val n = onset.size
    val pMin = max(1, (period / 2f).roundToInt())
    val pMax = (2f * period).roundToInt()
    val cost = FloatArray(pMax + 1)
    for (p in pMin..pMax) { val l = ln(p / period); cost[p] = -alpha * l * l }

    val cum = FloatArray(n)              // C*(t)
    val back = IntArray(n) { -1 }        // P*(t)
    for (t in 0 until n) {
        var best = -1e30f; var arg = -1
        for (p in pMin..min(pMax, t)) {
            val c = cum[t - p] + cost[p]
            if (c > best) { best = c; arg = t - p }
        }
        cum[t] = onset[t] + if (arg < 0) 0f else best   // Start frei: C* = O
        back[t] = arg
    }

    var e = n - 1
    for (t in max(0, n - pMax) until n) if (cum[t] > cum[e]) e = t
    val rev = ArrayList<Int>(n / 8)
    var i = e
    while (i >= 0) { rev.add(i); i = back[i] }
    rev.reverse()
    return rev.toIntArray()
}

// Zwei-Pass (nur bei Streuung > 2 %)
fun track(onset: FloatArray, bpm: Float): IntArray {
    val b1 = beatTrack(onset, 60f * 86.1328f / bpm)
    val ibi = b1.zipWithNext { a, b -> (b - a).toFloat() }
    val muI = ibi.average().toFloat()
    val sdI = sqrt(ibi.fold(0f) { s, v -> s + (v - muI) * (v - muI) } / ibi.size)
    if (sdI / muI <= 0.02f) return b1
    val period = (60f * 86.1328f) / bpm
    // Pass 2: F(p) gegen lokalen Median-Periode; hier als globale Nachjustierung
    return beatTrack(onset, period * (sdI / muI).coerceIn(0.95f, 1.05f))
}
```

---

## 3. BPM-Bereich 60–180, Faltung, 90 vs 180

**Faltung**: Der Bereich 60–180 umfasst 1,585 Oktaven. Jede Periode außerhalb wird durch ×2/÷2 hereingefaltet. Weil der Kamm alle Vielfachen von τ bewertet, ist **keine explizite Faltung nötig**: ein 240-BPM-Track hat ACF-Peaks bei τ(240) = 21,5, τ(120) = 43,1, τ(60) = 86,1; im Bereich 60–180 wird nur 120 geprüft, und der Kamm trifft dort 43,1/86,1/129,2 — alle drei. Der Kandidat 240 wird nie getestet, das Ergebnis ist trotzdem richtig. Genau das ist die gewünschte Halb-/Doppeltempo-Faltung, sie steckt in der Kamm-Summe.

**Warum der Prior zusätzlich nötig ist**: bei 180 BPM „borgt" der Kamm Score vom 90er-Niveau (r(2·29,7) = r(59,4) ist ein echter Peak). Für gleich starke Beats gilt r(28,7) ≈ r(57,4) ≈ r(86,1) ≈ ρ → S(180) ≈ S(90) ≈ 6ρ. Ohne Prior ist das ein Münzwurf. Mit W entscheidet 0,90 gegen 0,81 für 90.

**90 vs 180 — der eigentliche Test ist nicht der Score, sondern die Akzent-Alternation.**

A = |μ_ungerade − μ_gerade| / σ_gepoolt, über die Beat-Akzente a_i (§4).

| A | Interpretation | Ausgabe |
|---|---|---|
| 0,2–0,5 | echter Takt, alle Beats gleich stark | schnelles Niveau (180) |
| 1,0–2,0 | schnelles Niveau ist Unterteilung (Kick/Snare im Wechsel) | langsames Niveau (90) |
| 0,5–1,0 | unentschieden | dritter Test |

Zahlenbeispiel: 90-BPM-Groove, Kick auf 1 und 3, Snare auf 2 und 4. Auf dem Achtel-Raster (180) tragen die geraden Achtel die Snare (breitbandig, hoch), die ungeraden das Kick (tief). Im Low-Band-Akzent (§4) ist A ≈ 1,5–2 → 90 wird gemeldet. Derselbe Test bei einem echten 174-BPM-DnB-Track liefert A ≈ 0,3 → 174 bleibt.

**Dritter Test** (nur bei A ∈ [0,5; 1,0]): DP bei beiden Perioden laufen lassen und die **mittlere** Onset-Stärke je Beat vergleichen, nicht die Summe. Die Summen sind nicht vergleichbar, weil N verschieden ist — ein häufiger Fehler, der immer das schnellere Tempo gewinnt. μ_180/μ_90 > 1,3 → 180.

**Ausgabe**: bpm, bpmAlt (×2 oder ÷2, falls im Bereich), beide Konfidenzen. Die Ambiguität wird nicht „gelöst", sondern mitgeliefert — bei einem 174-BPM-Track ist 87 genauso richtig, und der Nutzer entscheidet.

---

## 4. Taktart 3/4 vs 4/4

**Akzentfolge** aus dem Low-Band (Bänder 1–4 = 100–324 Hz, Kick/Bass):

a_i = (1/τ_i)·Σ_{t=t_i}^{t_i+τ_i−1} O_low(t)

Robustisierung: â_i = (a_i − median(a)) / (1,4826·MAD(a)); |â_i| > 5 → durch Median ersetzen.

**Summenvergleich** (klassisch, der Vollständigkeit halber): A_φ = Σ_{i ≡ φ (mod B)} â_i; Kontrast = (max_φ A_φ − mean A)/std A. Das ist niveau- und längenabhängig und taugt nur als Diagnose.

**Entscheider** ist der normalisierte Summenvergleich, also die Akzent-Autokorrelation in Beat-Einheiten:

**ρ(B) = Σ_{i=0}^{N−B−1} (â_i − μ̂)(â_{i+B} − μ̂) / Σ_{i=0}^{N−1} (â_i − μ̂)²**,  B ∈ {3, 4}

ρ(B) ist skalenfrei und für alle B auf denselben Wertebereich normiert. B\* = argmax ρ(B).

**Phase**: φ\* = argmax_{φ<B} (1/|I_φ|)·Σ_{i∈I_φ} â_i, I_φ = {i : i ≡ φ (mod B)}. Taktposition = (i − φ\*) mod B.

**Konfidenz per Permutationstest** (kein Verteilungsmodell nötig, deshalb kein Hand-Waving):
1. â 400× zufällig permutieren (Fisher-Yates).
2. je Permutation ρ₃, ρ₄ berechnen.
3. p = #{Permutationen mit |ρ₄−ρ₃| ≥ beobachtet} / 400; c_meter = 1 − p.

Zahlen: N = 480 Beats (4 min bei 120 BPM). Unter der Nullhypothese σ_ρ ≈ 1/√(N−B) = 0,046; ein echtes ρ(4) = 0,35 ist 7,6σ → p unter der Auflösungsgrenze 0,0025. Kosten: 400·480·2 = 384 k Ops → 1–2 ms.

**Grenzen des Verfahrens** (alle, nicht die bequemen):
1. **Four-on-the-floor**: alle vier Akzente gleich → ρ(3) ≈ ρ(4) ≈ 0 → p ≈ 0,5 → c < 0,6. Dann 4/4 als Default melden, aber c_meter ≤ 0,6 ausgeben. Das Verfahren *kann* das nicht wissen.
2. **Nur 3 gegen 4.** 6/8, 12/8, 2/4 sind nicht unterscheidbar; 2/4 ist bei doppeltem Tempo identisch mit 4/4 und für Glyphen ohne Belang.
3. **Falsches metrisches Niveau**: hat der DP auf Achteln gelockt, ist die wahre Balkenlänge 8, also ρ(4) ≈ 0 und ρ(8) hoch. Erkennung: ρ(2B) > ρ(B) + 2σ → Niveau halbieren, neu rechnen. Ohne diesen Guard ist die Taktart bei Achtel-Raster **immer** falsch.
4. **Beat-Fehler** (Insertion/Deletion) brechen die Modulo-Indizierung. Gegenmittel: φ je 8-Beats-Block neu schätzen und Mehrheit über die Blöcke verlangen (≥ 60 %). Bei 4 min/120 BPM sind das 15 Blöcke.
5. **Auftakt/Anacrusis**: die Phase fängt ihn nur, wenn er kürzer als ein halber Takt ist.
6. **Ohne Schlagzeug** (Pad, Ambient): kein Akzent, c < 0,5, keinen Downbeat ausgeben.
7. **Tempowechsel**: ρ über den ganzen Track verschmiert; auf 16-Takt-Abschnitten rechnen und Mehrheit bilden.
8. Die Beat-Akzente stammen aus dem Low-Band; ein Stück ohne Bass, aber mit Becken-Akzenten (Jazz-Brush) wird falsch gefaltet. Zweitmeinung aus dem High-Band (Bänder 11–16) nur bei c_meter < 0,7.

---

## 5. Ausgabe, Glättung, Glyph-Anbindung

### 5.1 Datentyp

```kotlin
data class Beat(val timeSec: Float, val barPos: Int, val conf: Float)
data class BeatGrid(
    val bpm: Float, val bpmAlt: Float,
    val meter: Int, val meterConf: Float, val phase: Int,
    val confidence: Float, val beats: List<Beat>,
)
```

### 5.2 Konfidenzen (0..1, jede mit Formel)

- **je Beat**: c_i = clip((Õ(t_i) − μ_loc)/(3·σ_loc), 0, 1) — 0 am lokalen Mittel, 1 bei +3σ. μ_loc, σ_loc über ±2 s.
- **c_tempo** = clip(1 − S̃₂/S̃₁, 0, 1), wobei S̃₂ der beste Peak ist, dessen Periode **nicht** in [0,95·k·τ₁, 1,05·k·τ₁] für k = 1..4 liegt. Ohne diesen Ausschluss liefert ein perfekter Klicktrack c_tempo = 0, weil die harmonischen Peaks gleich hoch sind.
- **c_reg** = 1 − min(1, (σ_IBI/μ_IBI)/0,05)
- **c_onset** = Anteil der Beats mit c_i > 0,5
- **c = 0,4·c_tempo + 0,3·c_reg + 0,3·c_onset** — arithmetisch, nicht geometrisch: geometrisch wäre c = 0, sobald ein Term 0 ist, und das ist bei einem ruhigen Intro falsch.
- c_meter wird **separat** gemeldet und nicht in c eingemischt (Taktart ist eine andere Frage als Beat-Existenz).

### 5.3 Glättung, damit die Glyphen nicht zappeln

1. Blöcke von 8 Beats; je Block Theil–Sen-Gerade t_i = a + b·i (Median der 28 paarweisen Steigungen) → b = τ_block. Robust gegen einen verpassten Beat, im Gegensatz zu kleinster Quadrate.
2. Median-5 über die Blockperioden.
3. **Totband**: |τ_neu − τ_akt|/τ_akt ≤ 0,02 → τ_akt behalten. 2 % von 120 BPM = 2,4 BPM; die Tempogramm-Auflösung ist 0,5 BPM, also wird bei konstantem Tempo **nie** nachgestellt. Das ist der eigentliche Zappel-Schutz.
4. Bei Überschreitung: geometrisch über 4 Beats rampen, τ(i) = τ_akt·(τ_neu/τ_akt)^{(i−i₀)/4}, und die Phase **integrieren**: t_i = t_{i−1} + τ(i). Nie neu verankern — ein Sprung in t_i ist genau das Zappeln.
5. Kein Snap unter 20 ms: der Ticker läuft mit MIN_INTERVAL_MS = 20, feinere Beat-Zeiten werden ohnehin auf den nächsten Tick gerundet. Snap auf das lokale O-Maximum nur, wenn es > 1,2×O(t_i) ist und ≥ 1 Frame (11,6 ms) Abstand hat — sonst Rasterzeit behalten.

### 5.4 Glyph-Anbindung — was übernommen wird, was neu ist

**Übernommen, unverändert (nicht anfassen):**
- `glyph/NothingGlyph.kt` — bindService/init/register/openSession/setFrameColors/closeSession/turnOff, bound-Flag, `apply(IntArray)` mit `copyOf(spec.channels)`, `pending`-Rahmen vor der Sitzung, `off()`
- `glyph/Level.kt` — **MAX = 4000** (nicht 4096; 4096 ist nur die Obergrenze der Atemanimation), `Level.value(percent)` linear, `PERCENTS = [30, 65, 100]`. Achtung: die Funktion heißt `value(percent)`, nicht `raw()` — `Level.value(30) = 1200`.
- `glyph/GlyphOutput.kt`, `glyph/Selection.kt`
- `device/Channel.kt`, `device/DeviceCatalog.kt`, `device/DeviceDetector.kt` — 36 Kanäle auf dem 3a/3a Pro, `measured = true`, Streifen A (Index 20, 11 Kanäle, Arc 40°+90°), B (31, 5, 148°+46°), C (0, 20, 202°+169°)
- `ui/GlyphLayout.kt` — Slot-Berechnung und Treffer-Test
- `anim/Ticker.kt` — MIN_INTERVAL_MS = 20

**Neu:**
- `analysis/Spectrum.kt` — MediaExtractor/MediaCodec → Hann/2048/Hop 512 → radix-2-FFT → Betragsspektrum (laut Auftrag festgelegt; nicht Teil dieser Runde)
- `analysis/Onset.kt` — log-LUT, SuperFlux, Normalisierung
- `analysis/Tempogram.kt` — ACF, Lagrange, Kamm, Prior
- `analysis/BeatTrack.kt` — DP, Zwei-Pass, Beat-Zeiten
- `analysis/Meter.kt` — Akzente, ρ, Permutationstest
- `analysis/BeatGrid.kt` — Datentypen + Glättung
- `glyph/BeatPlayer.kt` — Flash-Scheduler: rechnet je Ticker-Tick (20 ms) aus dem BeatGrid ein `IntArray(36)` und ruft `GlyphOutput.apply()`

**Flash-Envelope** (das ist die Stelle, die zum vorhandenen SDK passt):

level_k(t) = clip(Σ_j A_j·exp(−(t − t_j)/τ_dec), 0, 4000)

A_j = Level.value(round(100·c_j)) → 0…4000 in 40er-Schritten; τ_dec = 80 ms; Haltezeit max(τ/4, 60 ms), geklemmt auf 120 ms (bei 120 BPM sind τ/4 = 125 ms → 120). Kanäle werden aus der **Taktposition** abgeleitet (barPos → Streifen A/B/C → Slot-Index), nie aus der Uhrzeit — sonst würfelt eine 2-%-Tempoänderung das Muster neu. Mindestabstand zweier Blitze 100 ms.

---

## 6. Rechenbudget, 4 Minuten, 4 Kerne, Kotlin ohne JNI

T = 20 672 Frames. Angenommen ein Cortex-A-Kern mit ~1,5 Gflop/s effektiv (skalarer Float-Loop, JVM). Die reale SoC hat 8 Kerne (1+3+4); gerechnet wird für 4 Threads, wie vorgegeben — die Einzelkernzahlen sind die relevanten.

| Posten | Operationen | Zeit |
|---|---|---|
| Onset-Flux, 512 Bins | 10,58 M Bin-Ops · 6 = 63 M | **30–50 ms** (mit log-LUT) |
| dito mit `Math.log` | 10,58 M logs à 15 ns | **160 ms** (LUT spart 120 ms) |
| ACF direkt, 260 Lags | 5,34 M MAC = 10,7 Mflop | **5–10 ms** |
| ACF naiv voll (L = T) | 213,6 M MAC | **250–400 ms** |
| ACF per FFT (65 536) | 5,3 Mflop, 2×256 KB Puffer | **3–6 ms** |
| Kamm 481 BPM × 3 | 5,8 k Ops | < 0,1 ms |
| DP, 66 Kandidaten/Frame | 1,36 M × 4 Ops | **2–4 ms** |
| Meter, 400 Permutationen | 0,77 M Ops | 1–2 ms |
| **Summe Tempo+DP+Meter** | | **~15 ms** (direkt) / ~20 ms (FFT) |
| Frontend (gegeben, Vergleich) | 20 672 FFTs à 2048 ≈ 1,16 Gflop | 0,6–1,0 s (1 Kern), 0,2–0,3 s (4 Kerne) |

**Anteil der Tempostufe an der Pipeline: < 3 %.** Nicht optimieren. Wenn doch, dann die log-LUT und die FFT — nicht die ACF.

**Autokorrelation naiv vs. FFT — die ehrliche Antwort**: break-even bei L\* = 5·log₂(2T) ≈ **77 Lags**. Wir brauchen 260, also ist die FFT theoretisch ~2× billiger (5,3 gegen 10,7 Mflop). Beide liegen unter 10 ms; die FFT braucht 512 KB Puffer und 120 Zeilen mehr. **Empfehlung: direkt** — 100 KB, 20 Zeilen, streamingfähig. Die FFT lohnt erst, wenn T um 10× wächst oder man L ~ T braucht (dann ist sie 40× billiger als naiv voll).

**Anker aus der Literatur statt Schätzung**: Böck & Widmer messen 2,0 s für 60 s Audio auf einem 2,26-GHz-Core-2-Kern (30× Echtzeit) — mit 200 fps und 138-Band-Filterbank. Unsere 4 min bei 86 fps ohne Filterbank sind auf dieser Hardware ~3 s, auf einem modernen Telefonkern 0,6–1,0 s. Realzeitfaktor **~300×**.

**Speicher**: O 83 KB, cum 83 KB, back 83 KB, ACF 1 KB → < 300 KB, kein GC-Druck. (Naiv-voll-ACF oder SSM wären 20 672² · 4 B = 1,7 GB — deshalb keine Selbstähnlichkeitsmatrix.)

---

## 7. Reihenfolge der Umsetzung

1. `analysis/Onset.kt`: Flux + log-LUT + Normalisierung; gegen einen Klicktrack prüfen (Peaks auf den Klicks, konstanten Versatz messen und als t_kalib eintragen).
2. `analysis/Tempogram.kt`: ACF + Kamm + Prior; gegen synthetische Klicktracks 60/90/120/174/180 BPM prüfen (Fehler < 0,5 BPM).
3. `analysis/BeatTrack.kt`: DP, Beats ausgeben, IBI-Streuung messen.
4. Zwei-Pass τ_loc, nur bei σ/μ > 2 %.
5. `analysis/Meter.kt`: Akzente, ρ(3)/ρ(4), Permutationstest, Niveau-Guard.
6. `analysis/BeatGrid.kt`: Theil–Sen, Totband, Rampen.
7. `glyph/BeatPlayer.kt`: 20-ms-Ticker, Envelope-Mixer, `GlyphOutput.apply()`.

Testbar ohne Gerät: 1–6 sind reine Float-Rechnung mit synthetischen Eingaben (Klicktrack, 3/4-Walzer, Four-on-the-floor). Nur 7 braucht das Telefon.


## formulas

- Log-Kompression: L(n,k) = ln(1 + λ·|X(n,k)|), λ = 1000, |X| so skaliert, dass ein Vollaussteuerungssinus |X| = 1 liefert. Wirkung: −60 dBFS → L = 0,693; 0 dBFS → L = 6,909 (Eingangsdynamik 1000:1 wird zu 10:1). λ = 1000 entspricht Böck & Widmer Gl. 4 log10(|X|·F+1) auf unnormierten DFT-Beträgen (dort |X| bis N/2 = 1024).
- SuperFlux-Onset-Hüllkurve: D(n,k) = L(n,k) − max{L(n−Δ,k−1), L(n−Δ,k), L(n−Δ,k+1)}; O_raw(n) = Σ_{k=1}^{512} max(0, D(n,k)); Δ = 1 Frame (11,6 ms, entspricht dem Paper mit Δ=2 bei 200 fps = 10 ms); k = 1..512 ↔ 21,5..11025 Hz.
- Normalisierung: μ(n) = (1/W)·Σ_{j=n−W/2}^{n+W/2−1} O_raw(j) mit W = 129 Frames (1,5 s ≈ 0,67 Hz Hochpass); O_hp = max(0, O_raw − μ); σ = StdAbw(O_hp); O_dp = O_hp/σ (nicht negativ, für den DP); Õ_acf = (O_dp − mean(O_dp))/σ (nullmittig, für die ACF). Zwei Envelopes, weil der DP bei nullmittigem O Beats ausdünnt (−Ō je weggelassenem Beat).
- Autokorrelation: r(τ) = 1/(T−τ) · Σ_{t=0}^{T−τ−1} Õ(t)·Õ(t+τ), τ = 1..260 Frames (260 = 3·τ(60)+2 für den Kamm). Division durch (T−τ) ist der erwartungstreue Schätzer; Bias bei τ = 86: 0,42 % (T = 20 672), 12,5 % (W = 8 s), 25 % (W = 4 s).
- Signifikanz der ACF: unter der Nullhypothese E[r] = 0, Var[r] ≈ 1/(T−τ) → z(τ) = r(τ)·√(T−τ) ist über alle Lags vergleichbar; r bleibt für die additive Kamm-Summe, z nur für Schwellen.
- Lagrange-3 (parabolisch) an den Stützstellen k−1, k, k+1 mit δ ∈ [0,1): p_k(δ) = r(k−1)·δ(δ−1)/2 + r(k)·(1−δ²) + r(k+1)·δ(δ+1)/2.
- Lagrange-Peak: δ* = 0,5·(r(k−1) − r(k+1)) / (r(k−1) − 2r(k) + r(k+1)); r(k+δ*) = r(k) − (r(k+1) − r(k−1))² / (8·(r(k+1) − 2r(k) + r(k−1))); Guard |Nenner| < 1e-6 → δ* = 0. Nötig, weil 1 Frame bei 120 BPM 2,32 % der Periode ist (180 BPM: 3,48 %; 60 BPM: 1,16 %).
- Kammfilter-Energie = ACF-Summe: E(τ) = Σ_t (Σ_{m=0}^{M−1} O(t−mτ))² = M·r(0) + 2·Σ_{d=1}^{M−1} (M−d)·r(dτ). Daraus S(τ) = Σ_{d=1}^{3} (4−d)·r(dτ) = 3r(τ) + 2r(2τ) + r(3τ) für M = 4. Keine Filterbank nötig.
- Perzeptuelle Gewichtung (Ellis Gl. 6): W(bpm) = exp(−½·(log₂(bpm/120)/σ_τ)²), σ_τ = 0,9 Oktaven; S̃(bpm) = W(bpm)·S(τ(bpm)) mit τ(bpm) = 60·f_f/bpm, f_f = 86,1328 Hz. W(60) = 0,539; W(90) = 0,899; W(120) = 1,000; W(180) = 0,810.
- Ellis-Kostenfunktion (Gl. 1): C({t_i}) = Σ_i O(t_i) + α·Σ_{i≥2} F(t_i − t_{i−1}, τ_p); Übergangsstrafe (Gl. 2): F(Δt, τ_p) = −(ln(Δt/τ_p))².
- DP-Rekursion (Ellis Gl. 3/4): C*(t) = O(t) + max_{p ∈ [τ_p/2, 2τ_p]} { α·F(p, τ_p) + C*(t−p) }; P*(t) = argmax_p { α·F(p, τ_p) + C*(t−p) }. Zustand ist nur t; τ ist Entscheidungsvariable, keine Zustandsdimension.
- α-Herleitung: F(1±ε) = −(ln(1±ε))² → ±5 % = −0,00238α, ±10 % = −0,00908α, ±20 % = −0,03324α, Bereichsgrenze = −0,4805α. Mit α = 400 und O auf σ = 1: ±10 % kostet 3,63 σ (geschluckt), ±20 % 13,3 σ (verboten), Grenze 192 σ. α = 330..400; Ellis' 680 liegt im flachen Bereich.
- DP-Ende und Backtrace: t_N = argmax_{t ∈ [T−2τ_p, T]} C*(t); dann i ← P*(i), Beats rückwärts sammeln, bis P*(i) < 0; umdrehen. Backtrace-Länge = Beat-Anzahl N (~480 bei 4 min/120 BPM).
- Zwei-Pass-Tempo: τ_loc(t) = Median der IBI über die 8 Beats um t; zweiter DP mit F(p, τ_loc) = −(ln(p/τ_loc(t−p)))². Nur wenn σ_IBI/μ_IBI > 2 % (Optimalität entfällt, Ellis §5.1).
- Tempo-Alternative: bpmAlt = 2·bpm bzw. bpm/2, falls im Bereich 60–180; beide Scores ausgeben statt zu raten, weil die Oktav-Ambiguität real ist (174 vs 87 BPM).
- Akzent je Beat: a_i = (1/τ_i)·Σ_{t=t_i}^{t_i+τ_i−1} O_low(t), O_low aus Bändern 1–4 (100–324 Hz); robust: â_i = (a_i − median(a))/(1,4826·MAD(a)), |â_i| > 5 → Median.
- Akzent-Alternation (90 vs 180): A = |μ_ungerade − μ_gerade| / σ_gepoolt. A ∈ [0,2; 0,5] → schnelles Niveau ist der Takt; A > 1,0 → schnelles Niveau ist Unterteilung, langsames melden; dazwischen dritter Test μ_180/μ_90 > 1,3 (Mittelwerte, nie Summen).
- Taktart-Test: ρ(B) = Σ_{i=0}^{N−B−1} (â_i − μ̂)(â_{i+B} − μ̂) / Σ_{i=0}^{N−1} (â_i − μ̂)², B ∈ {3,4}; B* = argmax ρ(B). Skalenfrei, im Gegensatz zur rohen Summe A_φ = Σ_{i≡φ} â_i.
- Takt-Phase: φ* = argmax_{φ<B} (1/|I_φ|)·Σ_{i∈I_φ} â_i mit I_φ = {i : i ≡ φ (mod B)}; Taktposition = (i − φ*) mod B.
- Takt-Konfidenz per Permutationstest: â 400× permutieren, je Permutation ρ₃ und ρ₄ berechnen, p = #{|ρ₄−ρ₃| ≥ beobachtet}/400, c_meter = 1 − p. Bei N = 480 ist σ_ρ ≈ 1/√(N−B) = 0,046; ρ = 0,35 ist 7,6σ.
- Beat-Konfidenz: c_i = clip((Õ(t_i) − μ_loc)/(3·σ_loc), 0, 1), μ_loc/σ_loc über ±2 s.
- Gesamt-Konfidenz: c = 0,4·c_tempo + 0,3·c_reg + 0,3·c_onset, mit c_tempo = clip(1 − S̃₂/S̃₁, 0, 1) (S̃₂ = bester Peak außerhalb [0,95·k·τ₁, 1,05·k·τ₁], k = 1..4), c_reg = 1 − min(1, (σ_IBI/μ_IBI)/0,05), c_onset = Anteil Beats mit c_i > 0,5.
- Beat-Zeit: t_i = (i·H + N/2)/f_s + t_kalib, auf 1 ms gerundet. Fenstermitte ist Pflicht — i·H/f_s allein verschiebt um N/(2f_s) = 23,2 ms.
- Glättung: je 8 Beats Theil–Sen (Median der 28 paarweisen Steigungen) → τ_block; Median-5 über die Blöcke; Totband |τ_neu − τ_akt|/τ_akt ≤ 0,02; bei Änderung geometrische Rampe τ(i) = τ_akt·(τ_neu/τ_akt)^{(i−i₀)/4} und Phase integrieren t_i = t_{i−1} + τ(i).
- Glyph-Envelope: level_k(t) = clip(Σ_j A_j·exp(−(t−t_j)/τ_dec), 0, 4000) mit A_j = Level.value(round(100·c_j)), τ_dec = 80 ms, Haltezeit max(τ/4, 60 ms) ≤ 120 ms, Mindestabstand 100 ms.
- ACF-Break-even direkt vs. FFT: L* = 5·log₂(2T) ≈ 77 Lags. Direkt kostet 2·L·T Flops, FFT (reell, 2T auf nächste Zweierpotenz) ≈ 10·T·log₂(2T).

## parameters

- f_f = 44100/512 = 86,1328 Frames/s, Δt = 11,610 ms — aus dem gegebenen Frontend, nicht wählbar.
- N = 2048, H = 512, Binbreite 21,533 Hz, Bins 1..512 = 21,5..11025 Hz — Bins 1–4 (21–86 Hz) sind nötig, weil die Kick-Fundamentale unter dem 100-Hz-Bandrand liegt.
- λ = 1000 (Log-Kompression) — identisch mit Böck & Widmer log10(|X|+1) auf unnormierten DFT-Beträgen, auf |X|_norm = |X|/N umgerechnet.
- Δ = 1 Frame für die SuperFlux-Differenz — effektiver Abstand 11,6 ms wie im Paper (2×5 ms); Δ = 2 wäre 23 ms und würde Onsets verschmieren.
- Max-Filter 3 Bins (±21,5 Hz) — wie beauftragt; für Perkussion ausreichend, weil kein Vibrato. Paper-exakt wäre m(k) = max(1, round(0,0293·k)).
- W = 129 Frames (1,5 s) für den gleitenden Mittelwert — ~0,67 Hz Hochpass, entfernt Strophe/Refrain-Pegelsprünge ohne Beats zu zerstören.
- ACF-Lags 1..260 — 260 = 3·86,13 + 2, die dritte Harmonische bei 60 BPM.
- Kamm M = 4 Taps, Gewichte 3/2/1 — folgt aus der Kamm-Energie E(τ) = M·r(0) + 2Σ(M−d)r(dτ); Ellis' 0,5/0,25 sind die abgeschnittene Variante.
- BPM-Raster 60..180 in 0,25 BPM (481 Werte), danach Lagrange → ~0,02 BPM — 0,25 BPM = 0,2 % bei 120, feiner als die Frame-Quantisierung nach Interpolation.
- σ_τ = 0,9 Oktaven — Ellis' getunter Wert für die TPS2/TPS3-Kombination (1,4 gilt nur für die reine TPS). Ergibt W(60) = 0,539, W(180) = 0,810.
- α = 400 (Bereich 330..680) — hergeleitet: ±10 % Tempoabweichung soll ~3 σ kosten, ein Onset-Peak ist 2–3 σ. Ellis' 680 liegt im flachen Optimum 100..2000.
- Suchbereich p ∈ [round(τ_p/2), round(2τ_p)] — Ellis §2; bei 120 BPM 21..86 = 66 Kandidaten je Frame.
- Tempo-Kurve: W = 8 s (689 Frames), Hop 1 s — 8 s = 8 Perioden bei 60 BPM; Bias dort 12,5 %, wird per 1/(W−τ) korrigiert.
- Zwei-Pass nur bei σ_IBI/μ_IBI > 2 % — darunter ist ein starres Raster der bessere Output (Zappel-Schutz).
- Taktart-Kandidaten B ∈ {3,4}, Permutationen 400 — p-Auflösung 0,0025, Kosten 1–2 ms bei N = 480.
- Niveau-Guard: ρ(2B) > ρ(B) + 2σ → Niveau halbieren und neu rechnen — ohne das ist die Taktart bei Achtel-Raster immer falsch.
- Mehrheit der Phase über 8-Beats-Blöcke, Schwelle 60 % — 15 Blöcke bei 4 min/120 BPM.
- Totband 2 % Tempoänderung, Rampe über 4 Beats, Median-5 über 8-Beat-Blöcke — 2 % von 120 BPM = 2,4 BPM, größer als die 0,5-BPM-Auflösung des Tempogramms: bei konstantem Tempo wird nie nachgestellt.
- Beat-Blitz: Haltezeit max(τ/4, 60 ms) ≤ 120 ms, Abklingzeit τ_dec = 80 ms, Mindestabstand 100 ms — bei 20-ms-Ticker sind das 3–6 Frames je Blitz.
- Helligkeit über Level.value(percent), MAX = 4000 — 40 Rohwerte je Prozent; Level.value(30) = 1200 ist am Gerät bestätigt. 4096 ist nur die Obergrenze der Atemanimation, nicht der SDK-Vorgabewert.
- Ticker-Intervall 20 ms (Ticker.MIN_INTERVAL_MS) — begrenzt die nutzbare Beat-Zeitgenauigkeit auf 20 ms; feinere Rasterzeiten sind für die Anzeige wertlos, für die Periodenschätzung nicht.
- Kanäle 36 auf dem Phone (3a)/(3a) Pro: A = Index 20, 11 Kanäle, Arc 40°+90°; B = Index 31, 5 Kanäle, Arc 148°+46°; C = Index 0, 20 Kanäle, Arc 202°+169° — aus DeviceCatalog.PHONE_3A, measured = true.
- Übernommen ohne Änderung: glyph/NothingGlyph.kt, glyph/Level.kt, glyph/GlyphOutput.kt, glyph/Selection.kt, device/Channel.kt, device/DeviceCatalog.kt, device/DeviceDetector.kt, ui/GlyphLayout.kt, anim/Ticker.kt. Neu: analysis/Spectrum.kt (gegebenes Frontend), analysis/Onset.kt, analysis/Tempogram.kt, analysis/BeatTrack.kt, analysis/Meter.kt, analysis/BeatGrid.kt, glyph/BeatPlayer.kt.

## pitfalls

- Zwei Envelopes, nicht eine: die ACF braucht Nullmittel (sonst dominiert μ²·(T−τ) die kurzen Lags), der DP darf nicht nullmittig sein (sonst kostet jeder weggelassene Beat −Ō und der DP dünnt Beats aus). Aus derselben O_raw zwei normalisierte Reihen machen.
- Fenstermitte statt Frameanfang: t = (i·H + N/2)/f_s. Mit i·H/f_s liegt jeder Beat 23,2 ms zu früh. Der Restversatz ist konstant und wird einmalig mit einem Klicktrack kalibriert, nicht hergeleitet.
- Bias-Korrektur der ACF ist nur bei kurzen Fenstern wichtig: global 0,42 % (ignorierbar), 8 s = 12,5 %, 4 s = 25 %. Wer 4-s-Fenster ohne 1/(W−τ) rechnet, bekommt systematisch zu schnelle Tempi.
- DP-Summen sind über Tempoebenen hinweg nicht vergleichbar: bei 180 BPM gibt es doppelt so viele Beats wie bei 90, also ist Σ O(t_i) fast doppelt. Für 90-vs-180 nur Mittelwerte je Beat oder das Tempogramm vergleichen — sonst gewinnt immer das schnellere Tempo.
- Kamm-Summe borgt Score vom Nachbar-Niveau: bei 180 BPM ist r(2τ) = r(59,4) ein echter Peak des 90er-Niveaus, deshalb S(180) ≈ S(90). Ohne den Prior (σ = 0,9) ist das ein Münzwurf; ohne den Alternationstest ist die Entscheidung nicht belegt.
- Oktav-Ambiguität ist real und nicht auflösbar: 174 vs 87 BPM, 90 vs 180. bpmAlt mit Konfidenz ausgeben statt eine Antwort zu erzwingen.
- Achtel-Raster zerstört die Taktart: hat der DP auf Unterteilungen gelockt, ist ρ(4) ≈ 0 und ρ(8) hoch — die Taktart wäre immer falsch. Guard ρ(2B) > ρ(B) + 2σ einbauen.
- Four-on-the-floor hat keine Taktart-Information: alle vier Akzente gleich → ρ(3) ≈ ρ(4) ≈ 0, p ≈ 0,5. Dann 4/4 als Default melden, aber c_meter ≤ 0,6 ausgeben statt eine Scheinsicherheit.
- Beat-Fehler brechen die Modulo-Indizierung: eine Insertion verschiebt alle folgenden Taktpositionen. Phase je 8-Beats-Block neu schätzen und Mehrheit (≥ 60 %) verlangen.
- Nachbeben der Wahl der Analysefenster: das 46,4-ms-Hann-Fenster ist bereits die Glättung — kein zusätzlicher 20-ms-Gauss wie bei Ellis, der würde nur die Zeitauflösung kosten.
- Math.log ist der teuerste Posten der Onset-Stufe: 10,58 M Aufrufe à ~15 ns = 160 ms. Eine 4096-Einträge-LUT über die oberen 12 Bit des IEEE-754-Floats mit linearer Interpolation (Fehler < 0,5 %) kostet 30–50 ms.
- ACF naiv über alle Lags ist 40× teurer als nötig: 213,6 M MAC (250–400 ms) gegen 5,34 M MAC bei 260 Lags (5–10 ms). Nur die Lags rechnen, die der Kamm braucht.
- FFT-ACF braucht die nächste Zweierpotenz über 2T: 65 536 Punkte, nicht 41 340 — sonst zirkuläre Faltung und falsche Lags bei kleinen τ.
- Selbstähnlichkeitsmatrix/Beat-Spectrum ist hier unmöglich: 20 672² · 4 B = 1,7 GB. Deshalb ACF statt SSM.
- Ticker-Grenze 20 ms beachten: Beat-Zeiten unter 20 ms zu verfeinern bringt für die Anzeige nichts. Die Lagrange-Verfeinerung gehört in die Periodenschätzung, nicht in die Blitzzeit.
- Level.value(), nicht Level.raw(): die Funktion heißt value(percent); MAX = 4000 (SDK-Vorgabewert), 4096 ist nur die Obergrenze der Atemanimation. Rohwerte über 4000 sind nicht belegt.
- Kanäle aus der Taktposition ableiten, nie aus der Uhrzeit: sonst würfelt eine 2-%-Tempoänderung das Blitzmuster neu und es sieht aus wie Zappeln.
- setFrameColors erwartet genau spec.channels Werte (36 auf dem 3a Pro); NothingGlyph.apply() kopiert bereits mit copyOf(spec.channels), also kein eigenes Auffüllen nötig — aber auch kein Array für ein anderes Gerät übergeben.
- Kein Snap unter einem Frame: ein Beat auf ein lokales O-Maximum zu ziehen, das nur 5 % höher ist, erzeugt genau das Zittern, das die Glättung verhindern soll. Schwelle 1,2× und mindestens 1 Frame Abstand.

## cost

4 Minuten Audio = 240 s = 20 672 Frames bei 86,1328 fps. Onset-Flux über 512 Bins: 10,58 M Bin-Operationen à ~6 Ops = 63 M Ops → 30–50 ms mit Log-LUT, 160 ms mit Math.log (die LUT spart 120 ms und ist damit die einzige Optimierung, die sich in dieser Stufe lohnt). ACF direkt über 260 Lags: 5,34 M MAC = 10,7 Mflop → 5–10 ms. ACF naiv über alle Lags: 213,6 M MAC → 250–400 ms (40× teurer, unnötig). ACF per FFT: 65 536-Punkt-Real-FFT, 5,3 Mflop → 3–6 ms, aber 2×256 KB Puffer; break-even liegt bei L* = 5·log₂(2T) ≈ 77 Lags, wir brauchen 260, also ist die FFT theoretisch ~2× billiger — bei beiden unter 10 ms entscheidet der Speicher, nicht die Zeit: direkt nehmen (100 KB, 20 Zeilen). Kamm + Prior: 481 BPM × 3 Harmonische = 5,8 k Ops, < 0,1 ms. DP: 20 672 Frames × 66 Kandidaten = 1,36 M Iterationen à ~4 Ops → 2–4 ms; Backtrace 480 Schritte, vernachlässigbar. Meter: 400 Permutationen × 480 Beats × 2 Lags = 0,77 M Ops → 1–2 ms. Summe Tempo + DP + Meter: ~15 ms (direkt) bzw. ~20 ms (FFT) auf einem Cortex-A-Kern bei ~1,5 Gflop/s effektiv, Kotlin ohne JNI. Zum Vergleich das bereits festgelegte Frontend: 20 672 FFTs à 2048 Punkte ≈ 1,16 Gflop → 0,6–1,0 s auf einem Kern, 0,2–0,3 s auf vier. Die ganze Tempostufe ist damit unter 3 % der Pipeline — nicht optimieren. Literaturanker statt Schätzung: Böck & Widmer messen 2,0 s für 60 s Audio auf einem 2,26-GHz-Core-2-Kern (30× Echtzeit) mit 200 fps und 138-Band-Filterbank; unsere 4 min bei 86 fps ohne Filterbank entsprechen auf dieser Hardware ~3 s, auf einem modernen Telefonkern 0,6–1,0 s, also Realzeitfaktor ~300×. Speicher: O 83 KB + cum 83 KB + back 83 KB + ACF 1 KB < 300 KB, kein GC-Druck. Die reale SoC hat 8 Kerne (1+3+4); gerechnet wurde für 4 Threads wie vorgegeben, die Einzelkernzahlen oben sind die maßgeblichen.
