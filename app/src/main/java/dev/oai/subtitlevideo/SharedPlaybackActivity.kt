package dev.oai.subtitlevideo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.oai.subtitlevideo.audio.AudioChunkDecoder
import dev.oai.subtitlevideo.audio.SimpleVad
import dev.oai.subtitlevideo.model.WhisperModelManager
import dev.oai.subtitlevideo.model.WhisperModelSpec
import dev.oai.subtitlevideo.settings.AppSettings
import dev.oai.subtitlevideo.srt.SrtCodec
import dev.oai.subtitlevideo.srt.SubtitleEntry
import dev.oai.subtitlevideo.translation.LocalTranslator
import dev.oai.subtitlevideo.whisper.WhisperEngine
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
class SharedPlaybackActivity : Activity() {
    companion object {
        const val EXTRA_VIDEO_URI = "shared_video_uri"
        const val EXTRA_BASE_NAME = "shared_base_name"
        private const val PREFS = "current_project"
        private const val WHISPER_CHUNK_SECONDS = 20
        private val PLAYBACK_MODEL = WhisperModelSpec.BASE_Q5
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val subtitleHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings
    private lateinit var modelManager: WhisperModelManager
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fallbackButton: Button
    private var player: ExoPlayer? = null
    private var subtitleView: TextView? = null
    private var subtitleEntries: List<SubtitleEntry> = emptyList()
    private var lastSubtitleIndex = -2

    private val subtitleTicker = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            updateSubtitle(activePlayer.currentPosition)
            subtitleHandler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = AppSettings.load(this)
        modelManager = WhisperModelManager(this)
        buildProcessingUi()
        val videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let(Uri::parse)
        val baseName = intent.getStringExtra(EXTRA_BASE_NAME).orEmpty().ifBlank { "shared_video" }
        if (videoUri == null) return fail("共有動画を開けませんでした。")
        processForPlayback(videoUri, baseName)
    }

    override fun onStop() {
        player?.pause()
        subtitleHandler.removeCallbacks(subtitleTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (player != null) {
            subtitleHandler.removeCallbacks(subtitleTicker)
            subtitleHandler.post(subtitleTicker)
        }
    }

    override fun onDestroy() {
        subtitleHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildProcessingUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(TextView(this).apply { text = "字幕付き再生を準備中"; textSize = 22f })
        statusText = TextView(this).apply {
            text = "共有動画を解析します。"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(14))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(statusText)
        root.addView(progress)
        root.addView(TextView(this).apply {
            text = "動画フォルダには保存しません。視聴用の軽量Whisperモデルを使用します。"
            textSize = 12f
            setPadding(0, dp(10), 0, dp(10))
        })
        fallbackButton = Button(this).apply {
            text = "通常画面を開く"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@SharedPlaybackActivity, MainActivity::class.java)) }
        }
        root.addView(fallbackButton)
        setContentView(root)
    }

    private fun processForPlayback(videoUri: Uri, baseName: String) {
        worker.execute {
            runCatching {
                if (!modelManager.isReady(PLAYBACK_MODEL)) {
                    runOnUiThread { setProgress(1, "初回のみ高速視聴モデルを準備します（約60MB）") }
                    modelManager.download(PLAYBACK_MODEL, WhisperModelManager.DownloadControl()) { state ->
                        runOnUiThread { setProgress((state.percent * 20) / 100, "高速視聴モデルを準備中: ${state.percent}%") }
                    }
                }
                val source = transcribe(videoUri)
                saveSource(source)
                val translated = translateForPlayback(source)
                saveTranslated(translated)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("videoUri", videoUri.toString())
                    .putString("baseName", baseName)
                    .remove("outputUri")
                    .apply()
                translated
            }.onSuccess { translated ->
                runOnUiThread {
                    setProgress(100, "字幕の準備が完了しました。")
                    showPlayer(videoUri, translated)
                }
            }.onFailure { error ->
                runOnUiThread { fail("字幕付き再生の準備に失敗しました: ${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }

    private fun transcribe(uri: Uri): List<SubtitleEntry> {
        val all = mutableListOf<SubtitleEntry>()
        WhisperEngine(modelManager.modelFile(PLAYBACK_MODEL)).use { whisper ->
            AudioChunkDecoder(this).decode(
                uri = uri,
                chunkSeconds = WHISPER_CHUNK_SECONDS,
                onProgress = { percent -> runOnUiThread {
                    setProgress(20 + (percent * 20) / 100, "音声を読み取り中: $percent%")
                } },
            ) { samples, chunkStartMs ->
                val windows = SimpleVad.split(samples)
                windows.forEachIndexed { index, window ->
                    val absoluteStartMs = chunkStartMs + window.offsetMs
                    val chunkNumber = (chunkStartMs / (WHISPER_CHUNK_SECONDS * 1000L)).toInt() + 1
                    runOnUiThread {
                        setProgress(
                            (45 + (chunkNumber - 1) * 5).coerceAtMost(75),
                            "Whisperで字幕を作成中: ${formatPosition(absoluteStartMs)}付近 / 区間 $chunkNumber / 音声 ${index + 1}/${windows.size}",
                        )
                    }
                    all += whisper.transcribe(
                        samples = window.samples,
                        chunkStartMs = absoluteStartMs,
                        language = settings.recognitionLanguageCode,
                        wordTiming = false,
                    )
                }
            }
        }
        val normalized = all
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .mapIndexed { index, item -> item.copy(index = index + 1) }
        require(normalized.isNotEmpty()) { "字幕を認識できませんでした" }
        runOnUiThread { setProgress(80, "字幕を${settings.targetLanguageLabel}へ翻訳します。") }
        return normalized
    }

    private fun translateForPlayback(source: List<SubtitleEntry>): List<SubtitleEntry> = try {
        LocalTranslator(settings.recognitionLanguageCode, settings.targetLanguageCode).use { translator ->
            translator.translate(source) { percent -> runOnUiThread {
                setProgress(80 + (percent * 19) / 100, "${settings.targetLanguageLabel}字幕を準備中: $percent%")
            } }
        }
    } catch (error: IllegalArgumentException) {
        if (error.message?.contains("元言語と翻訳先が同じ") == true) source else throw error
    }

    private fun saveSource(entries: List<SubtitleEntry>) {
        projectDir().mkdirs()
        File(projectDir(), "source.srt").writeText(SrtCodec.format(entries), Charsets.UTF_8)
    }

    private fun saveTranslated(entries: List<SubtitleEntry>) {
        projectDir().mkdirs()
        File(projectDir(), "translated.srt").writeText(SrtCodec.format(entries), Charsets.UTF_8)
    }

    private fun projectDir() = File(filesDir, "current")

    private fun showPlayer(videoUri: Uri, entries: List<SubtitleEntry>) {
        subtitleEntries = entries
        lastSubtitleIndex = -2
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        subtitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 20f * settings.subtitleTextScale
            maxLines = settings.maxLines
            setPadding(dp(14), dp(6), dp(14), dp(6))
            if (settings.shadowPercent > 0) setShadowLayer(1f + settings.shadowPercent / 25f, 0f, 2f, Color.BLACK)
        }
        val bottomMargin = (resources.displayMetrics.heightPixels * (settings.subtitleBottomMarginPercent / 100f)).toInt().coerceAtLeast(dp(24))
        root.addView(subtitleView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = dp(12); marginEnd = dp(12); this.bottomMargin = bottomMargin
        })
        val editButton = Button(this).apply {
            text = "保存・編集"
            isAllCaps = false
            setOnClickListener { player?.pause(); startActivity(Intent(this@SharedPlaybackActivity, MainActivity::class.java)) }
        }
        root.addView(editButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); marginEnd = dp(12)
        })
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        subtitleHandler.removeCallbacks(subtitleTicker)
        subtitleHandler.post(subtitleTicker)
    }

    private fun updateSubtitle(positionMs: Long) {
        val index = findSubtitleIndex(positionMs)
        if (index == lastSubtitleIndex) return
        lastSubtitleIndex = index
        subtitleView?.text = if (index >= 0) subtitleEntries[index].text else ""
    }

    private fun findSubtitleIndex(positionMs: Long): Int {
        var low = 0
        var high = subtitleEntries.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = subtitleEntries[mid]
            when {
                positionMs < entry.startMs -> high = mid - 1
                positionMs >= entry.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun formatPosition(positionMs: Long): String {
        val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun setProgress(value: Int, message: String) {
        progress.progress = maxOf(progress.progress, value.coerceIn(0, 100))
        statusText.text = message
    }

    private fun fail(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
        fallbackButton.visibility = View.VISIBLE
    }
}
