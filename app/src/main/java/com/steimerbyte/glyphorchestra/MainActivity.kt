package com.steimerbyte.glyphorchestra

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.steimerbyte.glyphorchestra.audio.AnalysisResult
import com.steimerbyte.glyphorchestra.device.DeviceDetector
import com.steimerbyte.glyphorchestra.device.DeviceSpec
import com.steimerbyte.glyphorchestra.device.Detection
import com.steimerbyte.glyphorchestra.glyph.GlyphOutput
import com.steimerbyte.glyphorchestra.glyph.GlyphProvider
import com.steimerbyte.glyphorchestra.glyph.GlyphStatus
import com.steimerbyte.glyphorchestra.player.GlyphOrchesterPlayer
import com.steimerbyte.glyphorchestra.ui.AnalysisRunner
import com.steimerbyte.glyphorchestra.ui.BandChartView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Eine Datei waehlen, sie analysieren, die Baender als Glyphen abspielen.
 *
 * Drei Regeln bestimmen den Aufbau:
 * - Analyse und Abspielen laufen nie gleichzeitig. Der Start des einen bricht
 *   den anderen ab; das ist eine Zustandsentscheidung hier, nicht im Player.
 * - Die Analyse rechnet im Hintergrund (Dispatchers.Default). Ein ueberholter
 *   Lauf wird nicht nur verworfen, sondern ueber [AnalysisRunner] am Lesen
 *   gehindert -- sonst rechnete er unsichtbar weiter.
 * - Das Glyph Developer Kit erlaubt Steuerung nur im Vordergrund: [onStart]
 *   verbindet, [onStop] trennt und haelt an.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spec: DeviceSpec
    private var output: GlyphOutput? = null
    private var player: GlyphOrchesterPlayer? = null

    private lateinit var connectionState: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var fileInfo: TextView
    private lateinit var pickButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var progressLine: TextView
    private lateinit var facts: TextView
    private lateinit var chart: BandChartView
    private lateinit var playButton: Button
    private lateinit var frameLine: TextView

    private var fileUri: Uri? = null
    private var fileName: String = ""
    private var fileDurationSec = 0.0
    private var result: AnalysisResult? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null
    private var analysisAbort: AtomicBoolean? = null

    /** Zaehlt jeden Start und jeden Abbruch; ein alter Lauf darf nichts mehr setzen. */
    private var analysisRun = 0
    private var lastPercent = -1

    /** Analyse-Dispatcher; der Test setzt hier Dispatchers.Unconfined ein. */
    internal var background: CoroutineDispatcher = Dispatchers.Default

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val detection = DeviceDetector.detect()
        spec = when (detection) {
            is Detection.Supported -> detection.spec
            is Detection.Matrix -> DeviceSpec.unsupported(detection.model)
            is Detection.Unknown -> DeviceSpec.unsupported(detection.model)
        }

        setContentView(buildUi())

        if (detection is Detection.Supported) {
            val glyph = GlyphProvider.create(this, spec).also { it.onStatus = ::renderConnection }
            output = glyph
            player = GlyphOrchesterPlayer(glyph).apply {
                onFrame = { frame, total ->
                    frameLine.text = getString(R.string.frame, frame + 1, total)
                    chart.setPlayhead(frame.toFloat() / total)
                }
                onFinished = { renderStopped() }
            }
        } else {
            renderConnection(
                GlyphStatus(
                    GlyphStatus.Tone.FAIL,
                    getString(R.string.connection_unsupported),
                    getString(R.string.unsupported_detail, spec.model),
                )
            )
        }

        renderFile()
        refreshButtons()

        // Letzte Datei wieder oeffnen: die persistierbare Freigabe aus
        // ACTION_OPEN_DOCUMENT bleibt ueber Neustarts erhalten.
        prefs().getString(KEY_FILE, null)?.let { selectFile(Uri.parse(it)) }
    }

    override fun onStart() {
        super.onStart()
        output?.connect()
    }

    override fun onStop() {
        // Erst anhalten, dann trennen: der Player schreibt sonst nach dem
        // Schliessen der Sitzung weiter, und der Rahmen bliebe stehen.
        stopPlayback()
        cancelAnalysis()
        output?.off()
        output?.disconnect()
        connectionState.text = getString(R.string.connection_off)
        connectionDetail.text = ""
        connectionState.setTextColor(ContextCompat.getColor(this, R.color.nothing_grey))
        super.onStop()
    }

    override fun onDestroy() {
        cancelAnalysis()
        scope.cancel()
        player?.release()
        output?.disconnect()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Datei

    /**
     * Uebernimmt eine gewaehlte Datei. Oeffentlich fuer den Picker-Rueckruf,
     * im Test direkt aufrufbar -- das ist die Naht, an der sonst ein
     * Systemdialog haengt.
     */
    internal fun selectFile(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        stopPlayback()
        cancelAnalysis()
        fileUri = uri
        fileName = readName(uri)
        fileDurationSec = 0.0
        result = null
        chart.clear()
        facts.visibility = View.GONE
        frameLine.text = ""
        progressLine.text = getString(R.string.state_idle)
        progressLine.setTextColor(ContextCompat.getColor(this, R.color.nothing_dim))
        renderFile()
        refreshButtons()
        prefs().edit().putString(KEY_FILE, uri.toString()).apply()
        loadDuration(uri)
    }

    private fun readName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    /** Die Dauer fuer die Kopfzeile; sie ist auch die Schaetzung fuer den Fortschritt. */
    private fun loadDuration(uri: Uri) {
        scope.launch {
            val seconds = withContext(background) {
                runCatching {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(this@MainActivity, uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLong()
                            ?.div(1000.0)
                    }
                }.getOrNull() ?: 0.0
            }
            if (fileUri != uri) return@launch
            fileDurationSec = seconds
            renderFile()
        }
    }

    private fun renderFile() {
        fileInfo.text = if (fileUri == null) {
            getString(R.string.file_none)
        } else {
            getString(R.string.file_info, fileName, formatDuration(fileDurationSec))
        }
    }

    // --------------------------------------------------------------- Analyse

    private fun analyze() {
        val uri = fileUri ?: return
        stopPlayback()
        cancelAnalysis()

        // Ein alter Lauf derselben Datei bleibt stehen und spielbar; erst die
        // neue Antwort ersetzt ihn. Das neue Dateiwaehlen hat ihn schon geleert.
        lastPercent = -1
        progressLine.text = getString(R.string.progress, 0)
        progressLine.setTextColor(ContextCompat.getColor(this, R.color.nothing_dim))

        val generation = ++analysisRun
        val abort = AtomicBoolean(false)
        analysisAbort = abort

        // LAZY: erst zuweisen, dann starten. Sonst koennte der Lauf (bei
        // sofort ausfuehrendem Test-Dispatcher) fertig sein, bevor das Feld
        // steht -- und der Knopf bliebe fuer immer gesperrt.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = withContext(background) {
                runCatching {
                    AnalysisRunner.run(
                        this@MainActivity,
                        uri,
                        spec,
                        fileDurationSec,
                        { fraction -> publishProgress(generation, fraction) },
                        abort::get,
                    )
                }
            }
            if (generation != analysisRun) return@launch

            analysisJob = null
            analysisAbort = null
            outcome.onSuccess { analysis ->
                result = analysis
                chart.setAnalysis(analysis)
                if (fileDurationSec <= 0.0) {
                    fileDurationSec = analysis.durationSec
                    renderFile()
                }
                renderFacts(analysis)
                progressLine.text = getString(R.string.analysis_done, analysis.frames)
                progressLine.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nothing_white))
            }.onFailure { error ->
                progressLine.text = getString(
                    R.string.analysis_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                progressLine.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nothing_red))
            }
            refreshButtons()
        }

        analysisJob = job
        job.start()
        refreshButtons()
    }

    /** Bricht einen laufenden Lauf wirklich ab: Flag fuer den Leser, Job fuer den Rest. */
    private fun cancelAnalysis() {
        val job = analysisJob ?: return
        analysisAbort?.set(true)
        analysisAbort = null
        analysisRun++
        job.cancel()
        analysisJob = null
        lastPercent = -1
        progressLine.text = getString(R.string.state_idle)
        progressLine.setTextColor(ContextCompat.getColor(this, R.color.nothing_dim))
        refreshButtons()
    }

    private fun publishProgress(generation: Int, fraction: Double) {
        val percent = (fraction * 100).roundToInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        // Aus dem Hintergrund: nur die Zahl in die Warteschlange legen, keine
        // Ansicht von fremdem Thread anfassen.
        progressLine.post {
            // Ein Prozentwert, der nach dem Ende eintrifft, darf die
            // Abschlusszeile nicht ueberschreiben.
            if (generation == analysisRun && analysisJob != null) {
                progressLine.text = getString(R.string.progress, percent)
            }
        }
    }

    private fun renderFacts(analysis: AnalysisResult) {
        facts.visibility = View.VISIBLE
        facts.text = getString(
            R.string.facts,
            analysis.bpm,
            analysis.meter,
            (analysis.confidence * 100).roundToInt(),
            analysis.beats.size,
            formatDuration(analysis.durationSec),
        )
    }

    // ------------------------------------------------------------- Abspielen

    private fun togglePlay() {
        val running = player ?: return
        if (running.isPlaying) {
            stopPlayback()
            refreshButtons()
            return
        }
        val analysis = result ?: return
        cancelAnalysis()
        running.play(analysis)
        frameLine.text = getString(R.string.frame, 1, analysis.frames)
        chart.setPlayhead(0f)
        refreshButtons()
    }

    private fun stopPlayback() {
        player?.stop()
        chart.setPlayhead(-1f)
        frameLine.text = ""
        playButton.text = getString(R.string.play)
    }

    /** Ende der Sequenz oder STOPP: zurueck in den wartenden Zustand. */
    private fun renderStopped() {
        player?.stop()
        chart.setPlayhead(-1f)
        frameLine.text = ""
        playButton.text = getString(R.string.play)
        refreshButtons()
    }

    private fun refreshButtons() {
        analyzeButton.isEnabled = fileUri != null && analysisJob == null
        playButton.isEnabled = result != null && output != null
        playButton.text = getString(if (player?.isPlaying == true) R.string.stop else R.string.play)
    }

    // ---------------------------------------------------------------- Geräte

    private fun renderConnection(status: GlyphStatus) {
        connectionState.text = status.headline
        connectionDetail.text = status.detail
        connectionState.setTextColor(
            ContextCompat.getColor(
                this,
                when (status.tone) {
                    GlyphStatus.Tone.READY -> R.color.nothing_white
                    GlyphStatus.Tone.WAIT -> R.color.nothing_grey
                    GlyphStatus.Tone.WARN, GlyphStatus.Tone.FAIL -> R.color.nothing_red
                },
            )
        )
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // Der Kicker ist der einzige Weg zur About-Sektion; ein eigener Knopf
        // waere ein zweiter Einstieg in dieselbe Auskunft.
        column.addView(
            label(getString(R.string.kicker), 9, R.color.nothing_dim).apply {
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.18f
                isClickable = true
                setOnClickListener { showAbout() }
            }
        )
        column.addView(
            label(getString(R.string.title), 24, R.color.nothing_white).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.1f
            }
        )

        connectionState = label(getString(R.string.connection_wait), 12, R.color.nothing_grey).apply {
            id = R.id.connection_state
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setPadding(0, dp(6), 0, 0)
        }
        connectionDetail = label("", 10, R.color.nothing_dim).apply {
            id = R.id.connection_detail
        }
        column.addView(connectionState)
        column.addView(connectionDetail)

        column.addView(sectionLabel(getString(R.string.label_file)))
        pickButton = actionButton(R.id.pick_button, getString(R.string.pick)) {
            picker.launch(arrayOf("audio/*"))
        }
        fileInfo = label("", 11, R.color.nothing_dim).apply {
            id = R.id.file_info
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(pickButton)
        column.addView(fileInfo)

        column.addView(sectionLabel(getString(R.string.label_analysis)))
        analyzeButton = actionButton(R.id.analyze_button, getString(R.string.analyze)) { analyze() }
        analyzeButton.setTextColor(ContextCompat.getColor(this, R.color.nothing_red))
        progressLine = label(getString(R.string.state_idle), 10, R.color.nothing_dim).apply {
            id = R.id.progress_line
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setPadding(0, dp(6), 0, 0)
        }
        facts = label("", 11, R.color.nothing_white).apply {
            id = R.id.facts
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        column.addView(analyzeButton)
        column.addView(progressLine)
        column.addView(facts)

        column.addView(sectionLabel(getString(R.string.label_sequence)))
        chart = BandChartView(this).apply {
            id = R.id.band_chart
            contentDescription = getString(R.string.chart_description)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(140),
            )
        }
        column.addView(chart)

        playButton = actionButton(R.id.play_button, getString(R.string.play)) { togglePlay() }
        playButton.setTextColor(ContextCompat.getColor(this, R.color.nothing_red))
        frameLine = label("", 10, R.color.nothing_dim).apply {
            id = R.id.frame_line
            typeface = Typeface.MONOSPACE
        }
        column.addView(playButton)
        column.addView(frameLine)

        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.nothing_black))
            // Ab targetSdk 35 zeichnet Android randlos; ohne diesen Satz
            // laege der Kicker unter der Statusleiste.
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun showAbout() {
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nothing_white))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), 0)
            text = aboutText()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    /** Pflichtinhalt der About-Sektion: Version, Leitspruch, Zweck, SDK-Aufrufe. */
    internal fun aboutText(): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        text.append(getString(R.string.about_version, BuildConfig.VERSION_NAME, spec.model))
        text.append("\n\n")

        val mottoStart = text.length
        text.append(getString(R.string.motto))
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.nothing_red)),
            mottoStart,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        text.append("\n\n")
        text.append(getString(R.string.about_purpose))
        text.append("\n\n")
        text.append(getString(R.string.about_calls, spec.constant, spec.channels))
        return text
    }

    private fun actionButton(id: Int, text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.id = id
            this.text = text
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            isAllCaps = true
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nothing_white))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.nothing_panel)
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) }
        }

    private fun label(text: String, sizeSp: Int, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
        }

    private fun sectionLabel(text: String): TextView =
        label(text, 9, R.color.nothing_dim).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.18f
            setPadding(0, dp(18), 0, dp(2))
        }

    private fun formatDuration(seconds: Double): String {
        if (seconds <= 0.0) return getString(R.string.duration_unknown)
        val total = seconds.roundToInt()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREFS = "glyph-orchestra"
        const val KEY_FILE = "last_file"
    }
}
