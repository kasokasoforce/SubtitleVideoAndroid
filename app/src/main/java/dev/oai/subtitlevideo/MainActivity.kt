package dev.oai.subtitlevideo

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import dev.oai.subtitlevideo.audio.AudioChunkDecoder
import dev.oai.subtitlevideo.export.VideoExporter
import dev.oai.subtitlevideo.model.WhisperModelManager
import dev.oai.subtitlevideo.model.WhisperModelSpec
import dev.oai.subtitlevideo.settings.AppSettings
import dev.oai.subtitlevideo.srt.SrtCodec
import dev.oai.subtitlevideo.srt.SubtitleEntry
import dev.oai.subtitlevideo.translation.TranslationShare
import dev.oai.subtitlevideo.whisper.WhisperEngine
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : Activity() {
    companion object {
        private const val REQ_VIDEO = 1001
        private const val REQ_JP_SRT = 1002
        private const val PREFS = "current_project"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var modelManager: WhisperModelManager
    private lateinit var exporter: VideoExporter
    private lateinit var settings: AppSettings

    private lateinit var statusText: TextView
    private lateinit var projectText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var settingsButton: Button
    private lateinit var modelButton: Button
    private lateinit var videoButton: Button
    private lateinit var transcribeButton: Button
    private lateinit var shareButton: Button
    private lateinit var importButton: Button
    private lateinit var pasteButton: Button
    private lateinit var exportButton: Button
    private lateinit var playButton: Button

    private var videoUri: Uri? = null
    private var baseName: String = "video"
    private var sourceEntries: List<SubtitleEntry>? = null
    private var japaneseEntries: List<SubtitleEntry>? = null
    private var outputUri: Uri? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.load(this)
        modelManager = WhisperModelManager(this)
        exporter = VideoExporter(this)
        buildUi()
        restoreProject()
        handleIncomingIntent(intent)
        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        exporter.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        content.addView(TextView(this).apply {
            text = "字幕動画メーカー"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "動画 → 端末内Whisper → ChatGPT翻訳 → 字幕焼き込み"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        })
        projectText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        statusText = TextView(this).apply {
            textSize = 14f
            minHeight = dp(72)
            setPadding(0, dp(12), 0, dp(12))
            movementMethod = ScrollingMovementMethod()
        }

        settingsButton = actionButton("設定 / 字幕プレビュー", ::showSettings)
        modelButton = actionButton("1. Whisperモデルを準備", ::downloadModel)
        videoButton = actionButton("2. 動画を選択", ::chooseVideo)
        transcribeButton = actionButton("3. 字幕を作成", ::transcribe)
        shareButton = actionButton("4. ChatGPTで翻訳", ::shareForTranslation)
        importButton = actionButton("5. 翻訳SRTファイルを読み込む", ::chooseJapaneseSrt)
        pasteButton = actionButton("5b. ChatGPT回答をクリップボードから取込", ::pasteJapaneseSrt)
        exportButton = actionButton("6. 字幕動画を作成", ::exportVideo)
        playButton = actionButton("完成動画を再生 / 共有", ::playOutput)

        content.addView(projectText)
        content.addView(progress)
        content.addView(statusText)
        listOf(settingsButton, modelButton, videoButton, transcribeButton, shareButton, importButton, pasteButton, exportButton, playButton)
            .forEach(content::addView)

        setContentView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        })
    }

    private fun showSettings() {
        if (busy) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(28, 28, 32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180),
            ).apply { bottomMargin = dp(12) }
        }
        val previewSubtitle = TextView(this).apply {
            text = "字幕はこのくらいの大きさで表示されます\n前後の会話に合わせて自然に翻訳"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
            setShadowLayer(3f, 0f, 2f, Color.BLACK)
        }
        preview.addView(previewSubtitle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        root.addView(preview)

        fun spinner(label: String, labels: List<String>, selected: Int): Spinner {
            root.addView(TextView(this).apply { text = label })
            return Spinner(this).also { sp ->
                sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                sp.setSelection(selected.coerceIn(0, labels.lastIndex))
                root.addView(sp)
            }
        }

        val recognitionLabels = listOf("自動判定", "中国語", "英語", "韓国語", "日本語")
        val recognitionCodes = listOf("auto", "zh", "en", "ko", "ja")
        val recognitionIndex = recognitionCodes.indexOf(settings.recognitionLanguageCode).takeIf { it >= 0 } ?: 1
        val recognitionSpinner = spinner("認識する音声言語", recognitionLabels, recognitionIndex)

        val targetLabels = listOf("日本語", "英語", "中国語", "韓国語")
        val targetCodes = listOf("ja", "en", "zh", "ko")
        val targetIndex = targetCodes.indexOf(settings.targetLanguageCode).takeIf { it >= 0 } ?: 0
        val targetSpinner = spinner("翻訳先", targetLabels, targetIndex)

        val models = WhisperModelSpec.entries.toList()
        val modelSpinner = spinner("Whisperモデル", models.map { it.displayName }, models.indexOf(settings.whisperModel))

        data class SliderRow(val seek: SeekBar, val value: TextView)
        fun slider(label: String, max: Int, progress: Int): SliderRow {
            val title = TextView(this).apply {
                text = label
                setPadding(0, dp(8), 0, 0)
            }
            val value = TextView(this)
            val seek = SeekBar(this).apply {
                this.max = max
                this.progress = progress.coerceIn(0, max)
            }
            root.addView(title)
            root.addView(value)
            root.addView(seek)
            return SliderRow(seek, value)
        }

        val size = slider("字幕サイズ", 90, ((settings.subtitleTextScale - 0.7f) * 100).toInt())
        val position = slider("字幕位置（下から）", 18, settings.subtitleBottomMarginPercent - 2)
        val lineChars = slider("1行の最大文字数", 28, settings.maxLineChars - 12)
        val lines = slider("最大行数", 2, settings.maxLines - 1)
        val shadow = slider("影の強さ", 100, settings.shadowPercent)
        val seconds = slider("1字幕の最大表示時間", 13, ((settings.maxEventSeconds - 1.5) * 2).toInt())

        fun updatePreview() {
            val textScale = 0.7f + size.seek.progress / 100f
            val bottom = 2 + position.seek.progress
            val chars = 12 + lineChars.seek.progress
            val maxLines = 1 + lines.seek.progress
            val shadowPct = shadow.seek.progress
            val eventSecs = 1.5 + seconds.seek.progress / 2.0
            size.value.text = "${"%.1f".format(textScale)}x"
            position.value.text = "$bottom%"
            lineChars.value.text = "${chars}文字"
            lines.value.text = "$maxLines 行"
            shadow.value.text = "$shadowPct%"
            seconds.value.text = "${"%.1f".format(eventSecs)}秒"
            previewSubtitle.textSize = 18f * textScale
            if (shadowPct == 0) previewSubtitle.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            else previewSubtitle.setShadowLayer(1f + shadowPct / 25f, 0f, 2f, Color.BLACK)
            val params = previewSubtitle.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = dp((180 * bottom / 100f).toInt())
            previewSubtitle.layoutParams = params
            previewSubtitle.maxLines = maxLines
            previewSubtitle.text = if (chars < 22) "字幕の見本です。長い文章は\n設定した文字数で折り返します" else
                "字幕はこのくらいの大きさで表示されます\n前後の会話に合わせて自然に翻訳"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        listOf(size, position, lineChars, lines, shadow, seconds).forEach { it.seek.setOnSeekBarChangeListener(listener) }
        updatePreview()

        AlertDialog.Builder(this)
            .setTitle("設定 / 字幕プレビュー")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val r = recognitionSpinner.selectedItemPosition
                val t = targetSpinner.selectedItemPosition
                settings = AppSettings(
                    recognitionLanguageCode = recognitionCodes[r],
                    recognitionLanguageLabel = recognitionLabels[r],
                    targetLanguageCode = targetCodes[t],
                    targetLanguageLabel = targetLabels[t],
                    whisperModel = models[modelSpinner.selectedItemPosition],
                    subtitleTextScale = 0.7f + size.seek.progress / 100f,
                    subtitleBottomMarginPercent = 2 + position.seek.progress,
                    maxLineChars = 12 + lineChars.seek.progress,
                    maxLines = 1 + lines.seek.progress,
                    shadowPercent = shadow.seek.progress,
                    maxEventSeconds = 1.5 + seconds.seek.progress / 2.0,
                )
                settings.save(this)
                setStatus("設定を保存しました。モデルを変更した場合は、そのモデルを準備してください。")
                refreshUi()
            }
            .show()
    }

    private fun downloadModel() {
        if (busy) return
        val spec = settings.whisperModel
        if (modelManager.isReady(spec)) {
            setStatus("${spec.displayName} は準備済みです。")
            return
        }
        setBusy(true, "${spec.displayName} をダウンロードしています。")
        worker.execute {
            runCatching {
                modelManager.download(spec) { percent ->
                    runOnUiThread { setProgress(percent, "Whisperモデル: $percent% (${spec.sizeMb}MB)") }
                }
            }.onSuccess {
                runOnUiThread {
                    setBusy(false, "Whisperモデルの準備が完了しました。")
                    refreshUi()
                }
            }.onFailure(::showBackgroundError)
        }
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_VIDEO)
    }

    private fun transcribe() {
        val uri = videoUri ?: return setStatus("先に動画を選択してください。")
        val spec = settings.whisperModel
        if (!modelManager.isReady(spec)) return setStatus("先に選択中のWhisperモデルを準備してください。")
        if (busy) return
        setBusy(true, "動画の音声を解析しています。")
        sourceEntries = null
        japaneseEntries = null
        clearSubtitleFiles()

        worker.execute {
            runCatching {
                val all = mutableListOf<SubtitleEntry>()
                WhisperEngine(modelManager.modelFile(spec)).use { whisper ->
                    AudioChunkDecoder(this).decode(
                        uri = uri,
                        onProgress = { percent -> runOnUiThread {
                            setProgress((percent * 15) / 100, "音声デコード中: $percent%")
                        } },
                    ) { samples, chunkStartMs ->
                        val minute = chunkStartMs / 60_000
                        runOnUiThread { setStatus("Whisperで文字起こし中: ${minute}分付近") }
                        all += whisper.transcribe(samples, chunkStartMs, language = settings.recognitionLanguageCode)
                    }
                }
                val normalized = all
                    .filter { it.text.isNotBlank() && it.endMs > it.startMs }
                    .sortedBy { it.startMs }
                    .mapIndexed { index, item -> item.copy(index = index + 1) }
                require(normalized.isNotEmpty()) { "字幕を認識できませんでした" }
                saveSource(normalized)
                normalized
            }.onSuccess { result ->
                runOnUiThread {
                    sourceEntries = result
                    setBusy(false, "字幕を作成しました: ${result.size}ブロック\n次は「ChatGPTで翻訳」を押してください。")
                    refreshUi()
                }
            }.onFailure(::showBackgroundError)
        }
    }

    private fun shareForTranslation() {
        val source = sourceEntries ?: return setStatus("先に字幕を作成してください。")
        val request = TranslationShare.buildRequestFile(
            this,
            SrtCodec.format(source),
            baseName,
            settings.targetLanguageLabel,
        )
        TranslationShare.shareToChatGpt(this, request, settings.targetLanguageLabel)
        setStatus("ChatGPTに${settings.targetLanguageLabel}への翻訳依頼を送ります。\n返答はSRTファイル、共有、またはクリップボードで戻せます。")
    }

    private fun chooseJapaneseSrt() {
        if (sourceEntries == null) return setStatus("元字幕がありません。先に文字起こししてください。")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain", "text/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_JP_SRT)
    }

    private fun importJapaneseSrt(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("翻訳SRTを読み込めません")
        }.onSuccess(::importJapaneseText)
            .onFailure { setStatus("翻訳SRTの読込に失敗しました: ${it.message}") }
    }

    private fun pasteJapaneseSrt() {
        if (sourceEntries == null) return setStatus("元字幕がありません。先に文字起こししてください。")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) return setStatus("クリップボードにテキストがありません。")
        importJapaneseText(text)
    }

    private fun importJapaneseText(raw: String) {
        val source = sourceEntries ?: return setStatus("元字幕がないため翻訳結果を取り込めません。")
        runCatching { SrtCodec.mergeTranslation(source, raw) }
            .onSuccess { merged ->
                japaneseEntries = merged
                saveJapanese(merged)
                setStatus("${settings.targetLanguageLabel}字幕を取り込みました: ${merged.size}ブロック\n時刻は元のWhisper字幕を保持します。")
                refreshUi()
            }
            .onFailure { setStatus("翻訳SRTの取込に失敗しました: ${it.message}") }
    }

    private fun exportVideo() {
        val uri = videoUri ?: return setStatus("動画が選択されていません。")
        val translated = japaneseEntries ?: return setStatus("翻訳SRTを先に読み込んでください。")
        if (busy) return
        setBusy(true, "字幕付きMP4を作成しています。")
        exporter.export(
            input = uri,
            subtitles = translated,
            baseName = baseName,
            settings = settings,
            onProgress = { percent -> setProgress(percent, "動画出力中: $percent%") },
            onCompleted = { resultUri ->
                outputUri = resultUri
                persistProject()
                setBusy(false, "完成しました。Movies/SubtitleVideo に保存しました。")
                refreshUi()
            },
            onError = { error ->
                setBusy(false, "動画出力に失敗しました: ${error.message}")
                refreshUi()
            },
        )
    }

    private fun playOutput() {
        val uri = outputUri ?: return setStatus("完成動画がありません。")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "動画を開く")) }
            .onFailure { setStatus("動画を開けませんでした: ${it.message}") }
    }

    @Deprecated("Legacy result API kept intentionally to avoid extra UI dependencies in v0.1")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_VIDEO -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                videoUri = uri
                baseName = queryName(uri).substringBeforeLast('.').ifBlank { "video" }
                sourceEntries = null
                japaneseEntries = null
                outputUri = null
                clearSubtitleFiles()
                persistProject()
                setStatus("動画を選択しました。")
                refreshUi()
            }
            REQ_JP_SRT -> importJapaneseSrt(uri)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || sourceEntries == null) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank() && sharedText.contains("-->")) {
            importJapaneseText(sharedText)
            return
        }
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) importJapaneseSrt(uri)
    }

    private fun saveSource(entries: List<SubtitleEntry>) {
        projectDir().mkdirs()
        sourceFile().writeText(SrtCodec.format(entries), Charsets.UTF_8)
    }

    private fun saveJapanese(entries: List<SubtitleEntry>) {
        projectDir().mkdirs()
        japaneseFile().writeText(SrtCodec.format(entries), Charsets.UTF_8)
        persistProject()
    }

    private fun restoreProject() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        videoUri = prefs.getString("videoUri", null)?.let(Uri::parse)
        baseName = prefs.getString("baseName", "video") ?: "video"
        outputUri = prefs.getString("outputUri", null)?.let(Uri::parse)
        sourceEntries = runCatching { if (sourceFile().isFile) SrtCodec.parse(sourceFile().readText()) else null }.getOrNull()
        japaneseEntries = runCatching { if (japaneseFile().isFile) SrtCodec.parse(japaneseFile().readText()) else null }.getOrNull()
    }

    private fun persistProject() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("videoUri", videoUri?.toString())
            .putString("baseName", baseName)
            .putString("outputUri", outputUri?.toString())
            .apply()
    }

    private fun projectDir() = File(filesDir, "current")
    private fun sourceFile() = File(projectDir(), "source.srt")
    private fun japaneseFile() = File(projectDir(), "translated.srt")
    private fun clearSubtitleFiles() {
        sourceFile().delete()
        japaneseFile().delete()
        File(projectDir(), "source.zh.srt").delete()
        File(projectDir(), "jp.srt").delete()
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "video.mp4"
        }
        return uri.lastPathSegment ?: "video.mp4"
    }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) progress.progress = 0
        setStatus(message)
        refreshUi()
    }

    private fun setProgress(value: Int, message: String) {
        progress.visibility = View.VISIBLE
        progress.progress = value.coerceIn(0, 100)
        setStatus(message)
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun refreshUi() {
        val hasVideo = videoUri != null
        val modelReady = runCatching { modelManager.isReady(settings.whisperModel) }.getOrDefault(false)
        modelButton.text = if (modelReady) "1. Whisperモデル: 準備済み (${settings.whisperModel.id})"
        else "1. Whisperモデルを準備 (${settings.whisperModel.sizeMb}MB)"
        transcribeButton.text = "3. ${settings.recognitionLanguageLabel}字幕を作成"
        shareButton.text = "4. ChatGPTで${settings.targetLanguageLabel}化"
        exportButton.text = "6. ${settings.targetLanguageLabel}字幕動画を作成"
        projectText.text = buildString {
            append("動画: ").append(if (hasVideo) baseName else "未選択")
            append("\nWhisper: ").append(settings.whisperModel.id).append(if (modelReady) " / 準備済み" else " / 未準備")
            append("\n音声: ").append(settings.recognitionLanguageLabel).append(" → 翻訳: ").append(settings.targetLanguageLabel)
            append("\n元字幕: ").append(sourceEntries?.size ?: 0).append("件 / 翻訳字幕: ").append(japaneseEntries?.size ?: 0).append("件")
        }
        settingsButton.isEnabled = !busy
        modelButton.isEnabled = !busy
        videoButton.isEnabled = !busy
        transcribeButton.isEnabled = !busy && hasVideo && modelReady
        shareButton.isEnabled = !busy && sourceEntries != null
        importButton.isEnabled = !busy && sourceEntries != null
        pasteButton.isEnabled = !busy && sourceEntries != null
        exportButton.isEnabled = !busy && hasVideo && japaneseEntries != null
        playButton.isEnabled = !busy && outputUri != null
    }

    private fun showBackgroundError(error: Throwable) {
        runOnUiThread {
            setBusy(false, "処理に失敗しました: ${error.message ?: error.javaClass.simpleName}")
            Toast.makeText(this, "処理に失敗しました", Toast.LENGTH_LONG).show()
            refreshUi()
        }
    }
}
