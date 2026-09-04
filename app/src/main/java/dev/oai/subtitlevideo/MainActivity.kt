package dev.oai.subtitlevideo

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import dev.oai.subtitlevideo.audio.AudioChunkDecoder
import dev.oai.subtitlevideo.export.VideoExporter
import dev.oai.subtitlevideo.model.WhisperModelManager
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

    private lateinit var statusText: TextView
    private lateinit var projectText: TextView
    private lateinit var progress: ProgressBar
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
        val title = TextView(this).apply {
            text = "字幕動画メーカー"
            textSize = 26f
        }
        val subtitle = TextView(this).apply {
            text = "動画 → 端末内Whisper → ChatGPT翻訳 → 字幕焼き込み"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        }
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
            minHeight = dp(80)
            setPadding(0, dp(12), 0, dp(12))
            movementMethod = ScrollingMovementMethod()
        }

        modelButton = actionButton("1. Whisperモデルを準備", ::downloadModel)
        videoButton = actionButton("2. 動画を選択", ::chooseVideo)
        transcribeButton = actionButton("3. 中国語字幕を作成", ::transcribe)
        shareButton = actionButton("4. ChatGPTで日本語化", ::shareForTranslation)
        importButton = actionButton("5. 日本語SRTファイルを読み込む", ::chooseJapaneseSrt)
        pasteButton = actionButton("5b. ChatGPT回答をクリップボードから取込", ::pasteJapaneseSrt)
        exportButton = actionButton("6. 日本語字幕動画を作成", ::exportVideo)
        playButton = actionButton("完成動画を再生 / 共有", ::playOutput)

        content.addView(title)
        content.addView(subtitle)
        content.addView(projectText)
        content.addView(progress)
        content.addView(statusText)
        listOf(modelButton, videoButton, transcribeButton, shareButton, importButton, pasteButton, exportButton, playButton)
            .forEach(content::addView)

        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
        setContentView(scroll)
    }

    private fun downloadModel() {
        if (busy) return
        if (modelManager.isReady()) {
            setStatus("Whisper smallモデルは準備済みです。")
            return
        }
        setBusy(true, "Whisper smallモデルをダウンロードしています（約488MB）。")
        worker.execute {
            runCatching {
                modelManager.download { percent -> runOnUiThread { setProgress(percent, "Whisperモデル: $percent%") } }
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
        if (!modelManager.isReady()) return setStatus("先にWhisperモデルを準備してください。")
        if (busy) return
        setBusy(true, "動画の音声を解析しています。")
        sourceEntries = null
        japaneseEntries = null
        clearSubtitleFiles()

        worker.execute {
            runCatching {
                val all = mutableListOf<SubtitleEntry>()
                WhisperEngine(modelManager.modelFile).use { whisper ->
                    AudioChunkDecoder(this).decode(
                        uri = uri,
                        onProgress = { percent ->
                            runOnUiThread { setProgress((percent * 15) / 100, "音声デコード中: $percent%") }
                        },
                    ) { samples, chunkStartMs ->
                        val minute = chunkStartMs / 60_000
                        runOnUiThread { setStatus("Whisperで文字起こし中: ${minute}分付近") }
                        all += whisper.transcribe(samples, chunkStartMs, language = "zh")
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
                    setBusy(false, "中国語字幕を作成しました: ${result.size}ブロック\n次は「ChatGPTで日本語化」を押してください。")
                    refreshUi()
                }
            }.onFailure(::showBackgroundError)
        }
    }

    private fun shareForTranslation() {
        val source = sourceEntries ?: return setStatus("先に中国語字幕を作成してください。")
        val sourceText = SrtCodec.format(source)
        val request = TranslationShare.buildRequestFile(this, sourceText, baseName)
        TranslationShare.shareToChatGpt(this, request)
        setStatus("ChatGPTに翻訳依頼を送ります。\n返ってきた日本語SRTを保存したら、このアプリへ戻って「日本語SRTを読み込む」を押してください。")
    }

    private fun chooseJapaneseSrt() {
        if (sourceEntries == null) return setStatus("元の中国語字幕がありません。先に文字起こししてください。")
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
                ?: error("日本語SRTを読み込めません")
        }.onSuccess(::importJapaneseText)
            .onFailure { setStatus("日本語SRTの読込に失敗しました: ${it.message}") }
    }

    private fun pasteJapaneseSrt() {
        if (sourceEntries == null) return setStatus("元の中国語字幕がありません。先に文字起こししてください。")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) return setStatus("クリップボードにテキストがありません。")
        importJapaneseText(text)
    }

    private fun importJapaneseText(raw: String) {
        val source = sourceEntries ?: return setStatus("元字幕がないため翻訳結果を取り込めません。")
        runCatching {
            SrtCodec.mergeTranslation(source, raw)
        }.onSuccess { merged ->
            japaneseEntries = merged
            saveJapanese(merged)
            setStatus("日本語字幕を取り込みました: ${merged.size}ブロック\nタイムコードは元のWhisper字幕を使用するため、ChatGPT側で時刻が変わっていても反映しません。")
            refreshUi()
        }.onFailure { setStatus("日本語SRTの取込に失敗しました: ${it.message}") }
    }

    private fun exportVideo() {
        val uri = videoUri ?: return setStatus("動画が選択されていません。")
        val jp = japaneseEntries ?: return setStatus("日本語SRTを先に読み込んでください。")
        if (busy) return
        setBusy(true, "字幕付きMP4を作成しています。")
        exporter.export(
            input = uri,
            subtitles = jp,
            baseName = baseName,
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
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
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
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null && sourceEntries != null) importJapaneseSrt(uri)
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
    private fun sourceFile() = File(projectDir(), "source.zh.srt")
    private fun japaneseFile() = File(projectDir(), "jp.srt")
    private fun clearSubtitleFiles() {
        sourceFile().delete()
        japaneseFile().delete()
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
        val modelReady = runCatching { modelManager.isReady() }.getOrDefault(false)
        modelButton.text = if (modelReady) "1. Whisperモデル: 準備済み" else "1. Whisperモデルを準備（約488MB）"
        projectText.text = buildString {
            append("動画: ").append(if (hasVideo) baseName else "未選択")
            append("\nWhisper: ").append(if (modelReady) "準備済み" else "未準備")
            append("\n中国語字幕: ").append(sourceEntries?.size ?: 0).append("件")
            append(" / 日本語字幕: ").append(japaneseEntries?.size ?: 0).append("件")
        }
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
