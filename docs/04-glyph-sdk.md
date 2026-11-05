# Berater-Bericht: GlyphSdk

## api

AAR: app/libs/glyph-matrix-sdk-2.0.aar, 114689 B, SHA-256 329393019db5f0f987c6245855d13fa273d06756c68829ca0f6ae686ba336da1 (gepinnt in scripts/fetch-gdk.sh). AndroidManifest: package com.nothing.thirdparty, minSdkVersion 33. classes.jar enthaelt drei Pakete: com.nothing.ketchum (das SDK), com.nothing.thirdparty (der AIDL-Vertrag zum Systemdienst), com.nothinglondon.text (25 Klassen Font/TextRenderer, ausschliesslich fuer Matrix-Text).

Das AAR enthaelt ZWEI getrennte APIs:
  A) Kanal-/Glyph-Light-API (GlyphManager + GlyphFrame) -> Phone (1), (2), (2a), (2a)+, (3a)/(3a) Pro, (4a), (4b). Fuer das 36-Kanal-Geraet (3a) ist das der einzige gangbare Weg.
  B) Matrix-API (GlyphMatrixManager + GlyphMatrixFrame/Object/Utils) -> nur Phone (3) 25x25 und Phone (4a) Pro 13x13. Auf der (3a) nicht verfuegbar (Common.getDeviceMatrixLength() liefert dort 0).

=== A) Kanal-API: com.nothing.ketchum.GlyphManager (Singleton, privater Konstruktor) ===
  public static GlyphManager getInstance(android.content.Context)
  public void init(GlyphManager.Callback)                              // bindService(Intent package com.nothing.thirdparty, action bind_glyphservice, ComponentName com.nothing.thirdparty/.GlyphService, flag 1)
  public void unInit()                                                 // nur unbindService(mConnection), keine Zustandspruefung
  public boolean register()                                            // liest Manifest-Meta "NothingKey" + Build.MODEL
  public boolean register(java.lang.String targetDevice)               // appKey + uebergebenes Modell
  public GlyphFrame.Builder getGlyphFrameBuilder()                     // null, solange mDevice null ist (d.h. vor register)
  public void openSession() throws GlyphException
  public void closeSession() throws GlyphException
  public void setFrameColors(int[]) throws GlyphException              // <<< der Schreibpfad: ein Pegel je Kanal
  public void turnOff()
  public void toggle(GlyphFrame)                                       // ein Frame, gewaehlte Kanaele auf DEFAULT_LIGHT
  public void animate(GlyphFrame)                                      // SDK-eigene Dreiecks-Rampe auf eigenem Thread
  public void displayProgress(GlyphFrame,int) / (GlyphFrame,int,boolean) / displayProgressAndToggle(GlyphFrame,int,boolean)

  Frame-Bau: com.nothing.ketchum.GlyphFrame.Builder
    GlyphFrame.Builder()                 // nimmt Build.MODEL, Array-Laenge = Common.getTargetDeviceGlyphChannelSize(Build.MODEL)
    GlyphFrame.Builder(String device)
    buildPeriod(int ms), buildCycles(int), buildInterval(int ms)
    buildChannel(int index)              // setzt Kanal auf GlyphFrame.DEFAULT_LIGHT
    buildChannel(int index, int light)   // Rohwert, KEINE Pruefung, KEIN Clamp
    buildChannelA() .. buildChannelE()   // Gruppen-Schnellwahl, ebenfalls DEFAULT_LIGHT
    build(): GlyphFrame
  GlyphFrame: getPeriod(), getCycles(), getInterval(), getChannel(): int[]
  Konstante: GlyphFrame.DEFAULT_LIGHT = 4000 (static final, im <clinit> als sipush 4000).
  GlyphManager-private Konstanten: DEFAULT_MAX_LIGHT = 4096, DEFAULT_MIN_LIGHT = 800, NO_LIGHT = 0, GLYPH_A..D = 1..4 -- im Bytecode NIRGENDS referenziert, also tote Konstanten; der einzige echte Vorgabewert ist 4000.

=== B) Matrix-API: com.nothing.ketchum.GlyphMatrixManager (Singleton) ===
  getInstance(Context), init(Callback), unInit()
  public boolean register(java.lang.String targetDevice)               // Glyph.DEVICE_23112 bzw. DEVICE_25111p
  public void setMatrixFrame(int[] color) throws GlyphException        // Erwartung laut offizieller Doku: 25x25 int
  public void setMatrixFrame(GlyphMatrixFrame)
  public void setAppMatrixFrame(int[] color) / setAppMatrixFrame(GlyphMatrixFrame)
  public void closeAppMatrix() throws GlyphException
  public void setGlyphMatrixTimeout(boolean) throws GlyphException
  public void turnOff()
  GlyphMatrixFrame: public int[] render()   // Laenge = Common.getDeviceMatrixLength()^2 (625 bzw. 169); Arrays gleicher Laenge werden per combineArrays(top,mid,low) ueberlagert (erster Nicht-Null gewinnt)
  GlyphMatrixFrame.Builder: addTop/addMid/addLow(GlyphMatrixObject | int[]), build(Context), buildWithMarquee(Context, Handler, int duration, int step, OnDataUpdateListener)
  GlyphMatrixObject.Builder: setImageSource(Bitmap), setText(String[,int[,Range]]), setTextStyle(String), setPosition(int,int), setOrientation(int), setScale(int 0..200), setBrightness(int 0..255), setReverse(boolean)
  GlyphMatrixUtils (static): convertToGlyphMatrix(Bitmap,int scale,int orientation,int brightness,int x,int y,boolean reverse,boolean), toGrayscaleArray(Bitmap,int), generateMatrixProgress(int,int,int,boolean,int[][]), createBitmapWithText(String,float,int), drawableToBitmap(Drawable), dilation/erosion, getLetterConfigs; Konstanten BRIGHTNESS_MULTIPLIER=16, MAX_BRIGHTNESS=4095, ON=4095, OFF=0.

=== Der eigentliche Binder-Vertrag: com.nothing.thirdparty.IGlyphService (AIDL) ===
  void setFrameColors(int[])                 // transact-Code 1, flag 0 -> synchron
  void openSession(), void closeSession()
  boolean register(String), boolean registerSDK(String appKey, String device), boolean registerMatrixSDK(String device)
  void setMatrixColors(int[]), void setGlyphMatrixTimeout(boolean), void setAppMatrixColors(int[]), void closeAppMatrix()

=== Zuordnung der Fragen ===
  Kanaele setzen: setFrameColors(int[]) -- Index i im Array = Kanal i, Wert = Pegel. GlyphManager.toggle/animate/displayProgress bauen intern genau so ein int[] und rufen denselben Pfad.
  Helligkeit setzen: es gibt KEINE eigene Helligkeits-API. Helligkeit ist der Wert im int[] je Kanal; global dimmen = jeden Wert skalieren. Matrix-Pfad: Helligkeit pro GlyphMatrixObject (0..255), im gerenderten int[] dann 0..4095.
  Sequenz/Frame schreiben: es gibt im Kanal-Pfad KEIN Sequenzobjekt und KEINE Warteschlange. GlyphFrame (period/cycles/interval) ist nur ein Parameterbuendel fuer animate()/toggle(). Eine Sequenz ist eine Folge einzelner setFrameColors-Aufrufe.
  Es gibt genau drei Komfort-Funktionen mit eigener Zeitlogik: animate(GlyphFrame), GlyphMatrixFrameWithMarquee.startMarquee() und GlyphMatrixNTypeUtils.animateText(...). Alle drei laufen im SDK, nicht im Dienst.

## limits

- TAKTUNG -- es gibt keine. Beleg: setFrameColors(int[]) besteht aus 8 Bytecode-Schritten (mHasAuthorized pruefen, mService!=null pruefen, stopCurrentTask(), IGlyphService.setFrameColors(arr), RemoteException fangen); kein Zaehler, kein Zeitstempel, keine Queue, kein Sleep. GlyphManager hat als Zustand nur mTask (Future) und mExecutor (Executors.newSingleThreadExecutor()). stopCurrentTask() = mTask.cancel(true), also nur der Abbruch der SDK-eigenen Animation, kein Rate-Limit.
- ZU SCHNELLE AUFRUFE -- kein Drop, keine Exception, kein Rueckgabewert (void). Jeder Aufruf ist eine eigene synchrone Binder-Transaktion: IGlyphService$Stub$Proxy.setFrameColors ruft IBinder.transact(1, data, reply, 0) mit flag 0 (nicht oneway) und liest danach reply.readException(). Der Aufrufer blockiert, bis der Dienst fertig ist. Frames werden ueberschrieben, nicht gepuffert: es gilt das zuletzt angekommene Array.
- STILLE NO-OP-FALLE: setFrameColors, openSession, closeSession und turnOff kehren SOFORT zurueck, wenn mHasAuthorized == false (Bytecode: if (!mHasAuthorized) { Log.d("GlyphManager","Non registed"); return; }). Nach einem abgelehnten register() schreibt die App also nichts und bekommt keine Exception -- nur eine Log-Zeile.
- GlyphException wird nur geworfen, wenn mService == null (Text: "Please use it after service connected."). Ein fehlendes openSession() meldet das SDK NICHT; der Aufruf geht trotzdem an den Dienst.
- SDK-EIGENE TAKTE (belegbare Obergrenzen, die Nothing selbst fuer machbar haelt): animate() im Bytecode von GlyphManager$3 -- Rampe mit Thread.sleep(10) je Frame, Pause mit 100 ms je Frame. animateText() in GlyphMatrixNTypeUtils -- ANIMATION_PIXELS_PER_SECOND = 40.0f, Frame-Intervall = 1000/40 ms = 25 ms (40 fps), Pacing per System.nanoTime() mit Abzug der Renderzeit. Marquee -- Handler.postDelayed(mTickRunnable, mDuration) je Schritt. Offizielles AnimationDemoService (GlyphMatrix-Example-Project) -- while(isActive){ frame bauen; setMatrixFrame auf Dispatchers.Main; delay(30) } = ca. 33 fps.
- KANAELE: 6 Segment-Geraete plus 2 Matrix-Geraete. Code_20111 (Phone 1) 15, Code_22111 (Phone 2) 33, Code_23111 (Phone 2a und 2a Plus) 26, Code_24111 (Phone 3a / 3a Pro) 36, Code_25111 (Phone 4a) 6, Code_25131 (Phone 4b) 4. Matrix: DEVICE_23112_MATRIX_LENGTH=25 -> 625 Pixel, DEVICE_25111p_MATRIX_LENGTH=13 -> 169 Pixel. Modellkennungen: A063, A065/AIN065, A142/A142P, A059, A069, A009P, A024, A069P.
- 36 KANAELE (Phone 3a / 3a Pro, DEVICE_24111 = A059), Indizes aus Glyph$Code_24111.<clinit>: C_1..C_20 = 0..19 (20 Kanaele, Bogen), A_1..A_11 = 20..30 (11 Kanaele, oberer Bogen), B_1..B_5 = 31..35 (5 Kanaele). Summe 36, lueckenlos. Gruppen im SDK: A, B, C. Deckt sich mit der Tabelle im offiziellen Glyph-Developer-Kit-README.
- HELLIGKEIT: ein int je Kanal, 0 = aus. Das SDK selbst schreibt bis 4096 (Rampe in GlyphManager$3 rechnet 0 -> 4096 -> 0), der Vorgabewert fuer buildChannel*/buildChannelA..E ist 4000. KEIN Clamp und KEINE Validierung: buildChannel(int,int) macht ArrayList.set(index, Integer.valueOf(light)), setFrameColors reicht das Array unveraendert an den Dienst. Negative Werte oder >4096 gehen also ungeprueft raus. Matrix-Pfad: GlyphMatrixObject.setBrightness 0..255 (Doku: ueber 255 wird gekappt), intern mal BRIGHTNESS_MULTIPLIER=16 -> 0..4080, Deckenkonstante MAX_BRIGHTNESS=4095, ON=4095. Array-Laenge Matrix = matrixLength^2.
- AUFLOESUNG der Helligkeit: nicht belegbar. Weder die AAR noch das offizielle README nennen eine Quantisierung des LED-Treibers. Belegt ist nur der angenommene Wertebereich 0..4096 (12 Bit).
- NEBENLAEUFIGKEIT: setFrameColors darf von jedem Thread gerufen werden; der Aufruf blockiert den aufrufenden Thread fuer die Dauer der Binder-Transaktion. Die ServiceConnection-Callbacks (onServiceConnected/onServiceDisconnected) kommen auf dem Main-Looper, weil init() keinen Handler uebergibt. Die SDK-eigenen Animationen laufen auf einem einzigen Executor-Thread (newSingleThreadExecutor), ein neuer animate()-Aufruf cancelt den laufenden per Future.cancel(true).
- DAUER EINES SCHREIBVORGANGS: nicht belegbar aus der AAR. Das AAR enthaelt nur den Client; der Dienst com.nothing.thirdparty.GlyphService liegt im Systemabbild. Nutzlast ist winzig (36 Kanaele x 4 B = 144 B Parcel), die Latenz wird also vom Dienst und vom LED-Treiber bestimmt, nicht vom IPC. Messvorschrift: System.nanoTime() um den setFrameColors-Aufruf, 300 Aufrufe, p50/p95 nehmen.
- DIENSTSEITIGE DROSSELUNG: nicht belegbar fuer dieses Geraet. Belegt ist nur: AOSP LightsService (services/core/java/com/android/server/lights/LightsService.java, main) hat KEIN Rate-Limit -- setLightStates geht direkt an die HAL. Fuer Nothing OS ist aus einer Reverse-Engineering-Doku (rec0de/glyph-api, Phone 2, Android 13) bekannt, dass setFrameColors bei JEDEM Aufruf erneut checkForeground(pkg) prueft und ohne offene Session bzw. ohne Registrierung still verwirft (Log "session is null" / "Non register"). Ob der Nachfolgedienst auf der 3a dasselbe tut, ist nicht belegbar.
- LEBENSZYKLUS: nur Vordergrund. Offizielles README: "Only foreground applications are allowed to be used." Ein Dienst oder eine laufende Audio-Wiedergabe im Hintergrund ist damit keine Option; das erklaert auch, warum Nothing seine eigene Atemanimation im Vordergrund-Client laufen laesst.
- GEBAUTE FRAMES: GlyphFrame kennt period (ms), cycles, interval (ms) -- reine Parameter fuer animate(). Die Rampe in GlyphManager$3: t = System.currentTimeMillis() - start; halb = period/2; v = (t < halb) ? (4096/halb)*t : 4096 - (4096/halb)*(t-halb); nur Kanaele mit Wert != 0 werden mit v ueberschrieben. Nach period folgt die Null-Folge im Takt von 100 ms, bis interval abgelaufen ist, dann der naechste Zyklus. Fallstrick: die SDK-Rampe haengt an der Wanduhr (currentTimeMillis), nicht an nanoTime.
- REGISTRIERUNG: Common.getAppKey liest Meta-Data "NothingKey" per PackageManager.getApplicationInfo(pkg, 128). Fehlt sie, ist der Key null und register() liefert false. Ab Android 16 entfaellt laut README die Schluesselpruefung; der Testschluessel "test" gilt nur zusammen mit adb shell settings put global nt_glyph_interface_debug_enable 1 (Debug-Modus schaltet sich nach 48 h selbst ab).
- TOTE KONSTANTEN im SDK: GlyphManager.DEFAULT_MAX_LIGHT/DEFAULT_MIN_LIGHT werden nirgends referenziert; Common.isSDKAvaliable(Context) wird von keiner Klasse aufgerufen (und waere zudem invertiert: es liefert true, wenn targetNothingSDKVersion < 140101 ist). Nicht darauf bauen.

## sequence_plan

Schreibpfad fuer 30 fps aus einer Audiodatei, zugeschnitten auf das 36-Kanal-Geraet (3a):

1) KEIN Ticker/Handler mehr fuer die Ausgabe. Ticker (anim/Ticker.kt) laeuft auf dem Main-Looper, MIN_INTERVAL_MS=20, aktuell TICK_MS=50 (20 Hz), und MainActivity.tickPulse ruft output.apply() direkt im UI-Thread. Bei 30 fps mit synchronem Binder blockiert das die Oberflaeche. Der Frame-Writer bekommt einen eigenen Thread (oder Executors.newSingleThreadScheduledExecutor) und ist der einzige, der setFrameColors ruft.

2) Zeitbasis: absolute Sollzeiten, keine Drift. Formel:
     T_ns   = 1_000_000_000 / fps            (bei 30 fps: 33_333_333 ns)
     t_n    = t_0 + n * T_ns
     sleep  = t_n - System.nanoTime()
   Ist sleep > 0: schlafen. Ist sleep <= -T_ns (ein ganzes Frame verpasst): n auf den naechsten Wert springen, der nicht in der Vergangenheit liegt -- Frames DROPPEN, niemals nachholen. Es gibt keine Warteschlange, weder im SDK noch sinnvollerweise in der App.

3) Frame-Quelle: ein IntArray(36) mit Pegeln 0..4000 (Level.MAX), Index = SDK-Index (C 0..19, A 20..30, B 31..35). Frame-Berechnung und Audio-Analyse sind Sache der anderen Bausteine; der Writer bekommt fertige Arrays aus einem Ringpuffer der Laenge 2 (aktuell + naechster). Ringpuffer statt Queue: bei Ueberlast wird der aeltere Frame verworfen, nicht gesammelt.

4) Latenzmessung vor der Festlegung von fps:
     long a = System.nanoTime(); manager.setFrameColors(frame); long b = System.nanoTime();
   300 Messungen, p50 und p95 bilden. Regel: fps_max = 1000 / (p95_ms + rechenzeit_ms). Solange p95 <= 20 ms ist, sind 30 fps mit Reserve machbar; ueber 30 ms p95 ist 30 fps nicht haltbar.

5) Lebenszyklus (genau diese Reihenfolge, sonst stiller No-Op):
     getInstance(app) -> init(callback) -> [in onServiceConnected] register("A059") -> register liefert true? sonst Status FAIL und NICHT weiter -> openSession() -> sessionFlag = true -> Writer starten.
   Vor JEDEM Schreibvorgang sessionFlag pruefen. Bei onServiceDisconnected: Writer stoppen, sessionFlag = false, nicht blind weiterschreiben. Beim Verlassen: Writer stoppen, closeSession(), unInit().

6) Writer-Pseudocode:
     fun run() {
       var n = 0L
       val t0 = System.nanoTime()
       while (running) {
         val target = t0 + n * T_ns
         val now = System.nanoTime()
         if (now > target + T_ns) { n += (now - target) / T_ns; continue }   // hinterher: Frames ueberspringen
         if (now < target) LockSupport.parkNanos(target - now)
         val frame = ring.takeLatest()
         if (frame != null && session) {
           try { manager.setFrameColors(frame) } catch (e: GlyphException) { /* Sitzung weg -> neu verbinden */ }
         }
         n++
       }
     }

7) Helligkeit: die Audio-Huellkurve auf 0..4000 abbilden (Level.MAX = 4000, 1 % = 40). Werte werden NICHT geklemmt -- die App muss selbst auf 0..4000 begrenzen, sonst gehen ungepruefte Zahlen an den Dienst. Untergrenze beachten: ein Kanal mit Wert 0 ist aus; fuer ein Glimmen mindestens 1 setzen (Pulse.frame macht das bereits so).

8) Was NICHT gebaut werden darf: kein animate()/toggle()/displayProgress() fuer die Wiedergabe. Diese drei bringen ihre eigene Zeitlogik mit, laufen auf dem SDK-Executor und wuerden mit dem eigenen Writer um dieselbe Sitzung konkurrieren (jeder Aufruf cancelt den laufenden SDK-Task per Future.cancel(true)). Fuer freie Sequenzen ist ausschliesslich setFrameColors richtig.

9) Testbarkeit ohne Geraet: die bestehende Naht GlyphOutput/NothingGlyph (glyph/NothingGlyph.kt) traegt weiter. Der Writer haengt hinter GlyphOutput.apply(IntArray); ein Test-Fake zaehlt Aufrufe und Zeitstempel, damit Pacing und Drop-Policy auf der JVM pruefbar sind.

## risks

- Hauptrisiko: die tatsaechliche Latenz von setFrameColors auf der 3a ist nicht belegbar. Die AAR beweist nur, dass das SDK nicht bremst -- nicht, dass der Dienst 30 Schreibvorgaenge/s durchlaesst. Vor dem Bau der Pipeline messen (Schritt 4 im Plan), sonst wird eine Framerate versprochen, die das Geraet nicht haelt.
- setFrameColors ist ein stiller No-Op, wenn register() false geliefert hat (mHasAuthorized-Guard). Ohne Statuspruefung sieht die App aus wie 'laeuft', die LEDs bleiben dunkel. Gleiches gilt fuer openSession(): das SDK prueft die Sitzung beim Schreiben NICHT.
- Blockierender Binder im UI-Thread ist der wahrscheinlichste Performance-Fehler: der bestehende Ticker laeuft auf dem Main-Looper, und bei 30 fps summiert sich die Transaktionszeit sichtbar. Writer zwingend auf einen eigenen Thread.
- Kein Clamp, keine Validierung: Werte ausserhalb 0..4096 und eine falsche Array-Laenge gehen ungeprueft an den Dienst. Die Laenge muss exakt 36 sein (DeviceSpec.channels), sonst spricht der Index die falsche LED an oder der Dienst verwirft still.
- Nur Vordergrund: sobald die Activity in den Hintergrund geht, ist die Sitzung laut offizieller Doku nicht mehr erlaubt. Eine 'Glyph Orchester'-Wiedergabe mit gesperrtem Bildschirm ist mit diesem SDK nicht umsetzbar (nicht belegbar, ob die 3a-Variante des Dienstes es doch toleriert -- der Reverse-Engineering-Stand zeigt eine Foreground-Pruefung bei jedem Aufruf).
- Die SDK-eigene animate()-Rampe haengt an System.currentTimeMillis(); ein Zeitwechsel (NTP, Zeitzone) kann die Rampe springen lassen. Ein eigener Writer mit nanoTime umgeht das -- ein weiterer Grund, animate() nicht fuer die Wiedergabe zu verwenden.
- Helligkeitsaufloesung des LED-Treibers ist nicht belegbar. 0..4096 ist der angenommene Wertebereich des SDK, nicht die nachgewiesene Aufloesung. Wer 'weiche' Verlaeufe verspricht, muss sie am Geraet gegenpruefen.
- Matrix-Geraete (Phone 3 = 625 Pixel, (4a) Pro = 169) haben einen anderen Pfad (setAppMatrixFrame, Objekt-Brightness 0..255, Doku verlangt Systemversion >= 20250801). Ein gemeinsamer Codepfad fuer Segment- und Matrix-Geraete ist nicht moeglich; die 3a bleibt beim Kanalpfad.
