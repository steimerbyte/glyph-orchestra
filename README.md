# Glyph Orchester

Musik rein, Licht raus. Die App liest eine Audiodatei, rechnet Takt, Tempo und
Spektrum aus und schickt daraus eine Blinkfolge auf die 36 Glyph-Segmente eines
Nothing-Telefons.

**Function over form.** Jedes Licht, das angeht, steht für etwas im Ton.

## Was gerechnet wird

Alles auf dem Gerät, offline, ohne Netz und ohne Konto.

| Schritt | Verfahren |
|---|---|
| Dekodieren | `MediaExtractor` + `MediaCodec`, Mono-Mix, keine Resampling-Stufe |
| Spektrum | Hann-Fenster N = 2048, Hop 512, radix-2-FFT in Kotlin, 86,1 Rahmen/s bei 44,1 kHz |
| Bänder | 16 logarithmische Bänder 100 Hz – 11 kHz plus ein Tieftonband 35–110 Hz für den Kick-Grundton |
| Onset | SuperFlux: log-Kompression (λ = 1000), Max-Filter über 3 Bins, Halbwellengleichrichtung, lokale Mittelwertentfernung |
| Tempo | Autokorrelation-Tempogramm, BPM 60–180, Beat-Tracking per Dynamic Programming |
| Takt | Akzentvektoren über die Beat-Positionen, 3/4 gegen 4/4 |
| Spannung | 0,38 Lautheit + 0,27 spektraler Schwerpunkt + 0,10 Rauheit + 0,25 Noveltät, geglättet |
| Ausgabe | 30 Bilder/s, je Bild 36 Helligkeiten 0–4000 |

Warum das Tieftonband: die Kick-Fundamentale liegt bei 50–90 Hz und damit
unterhalb des 100-Hz-Rasters. Ohne dieses Band wäre der stärkste Taktgeber
der meisten elektronischen Musik unsichtbar.

Warum die Fenstermitte als Zeitstempel: mit `i·H/fs` verschieben sich alle
Beats um `N/(2·fs)` = 23 ms. Mit `(i·H + N/2)/fs` stimmt der Bezug.

## Bedienung

1. Datei wählen (MP3, M4A, OGG, WAV).
2. **Analysieren** — läuft im Hintergrund, zeigt Fortschritt, danach BPM, Takt, Konfidenz.
3. **Abspielen** — die Glyphen folgen dem Stück, das Diagramm zeigt, was gerade läuft.

## Voraussetzungen

- Nothing-Telefon mit Glyph-Matrix (Phone (3a) Pro ist das Referenzgerät).
- Android 13 oder neuer.
- Kein Root, kein Shizuku. Die App nutzt die Glyph-Schnittstelle der Hersteller-SDK.

## Lizenz

MIT, siehe [LICENSE](LICENSE).

## Verwandt

Teil der Sammlung [ST-Nothing](https://github.com/steimerbyte/ST-Nothing).
