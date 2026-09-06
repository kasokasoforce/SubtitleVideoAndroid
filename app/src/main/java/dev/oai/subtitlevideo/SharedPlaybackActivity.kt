package dev.oai.subtitlevideo

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.oai.subtitlevideo.service.WhisperProcessingService
import dev.oai.subtitlevideo.settings.AppSettings
import dev.oai.subtitlevideo.srt.SrtCodec
import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.File

@UnstableApi
class SharedPlaybackActivity : Activity() {
    companion object {
        const val EXTRA_VIDEO_URI = "shared_video_uri"
        const val EXTRA_BASE_NAME = "shared_base_name"
        private const val REQ_RECORD_AUDIO = 2401
    }

    private val subtitleHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fallbackButton: Button
    private lateinit var videoUri: Uri
    private lateinit var baseName: String

    private var player: ExoPlayer? = null
    private var subtitleView: TextView? = null
    private var subtitleEntries: List<SubtitleEntry> = emptyList()
    private var lastSubtitleIndex = -2
    private var remoteWorker: Messenger? = null
    private var bound = false
    private var processingFinished = false
    private var destroying = false

    private val incomingMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            WhisperProcessingService.MSG_PROGRESS -> {
                setProgress(
                    message.data.getInt(WhisperProcessingService.KEY_PROGRESS, progress.progress),
                    message.data.getString(WhisperProcessingService.KEY_MESSAGE) ?: "字幕を処理中",
                )
                true
            }
            WhisperProcessingService.MSG_DONE -> {
                processingFinished = true
                loadResultAndPlay()
                true
            }
            WhisperProcessingService.MSG_ERROR -> {
                processingFinished = true
                fail(message.data.getString(WhisperProcessingService.KEY_MESSAGE) ?: "字幕処理に失敗しました。")
                true
            }
            else -> false
        }
    })

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                fail("字幕処理サービスへ接続できませんでした。")
                return
            }
            remoteWorker = Messenger(service)
            sendStartMessage()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteWorker = null
            bound = false
            if (!processingFinished && !destroying && player == null) {
                fail("字幕処理が異常終了しました。\n画面は維持しています。")
            }
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = fail("字幕処理サービスを開始できませんでした。")
    }

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
        buildProcessingUi()

        val parsedUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let(Uri::parse)
        if (parsedUri == null) {
            fail("共有動画を開けませんでした。")
            return
        }
        videoUri = parsedUri
        baseName = intent.getStringExtra(EXTRA_BASE_NAME).orEmpty().ifBlank { "shared_video" }
        startWithFastRecognitionPermission()
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
        destroying = true
        subtitleHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (bound) {
            runCatching { unbindService(serviceConnection) }
            bound = false
        }
        remoteWorker = null
        super.onDestroy()
    }

    private fun startWithFastRecognitionPermission() {
        if (Build.VERSION.SDK_INT < 34 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startRemoteProcessing()
            return
        }

        setProgress(0, "Android音声認識を優先するため、マイク権限を確認します。")
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RECORD_AUDIO) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setProgress(0, "Android音声認識を優先して処理します。")
        } else {
            setProgress(0, "マイク権限がないため、Whisperで処理します。")
        }
        startRemoteProcessing()
    }

    private fun startRemoteProcessing() {
        if (bound) return
        setProgress(0, "字幕処理サービスを開始しています。")
        val serviceIntent = Intent(this, WhisperProcessingService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) fail("字幕処理サービスへ接続できませんでした。")
    }

    private fun sendStartMessage() {
        val target = remoteWorker ?: return
        val data = Bundle().apply {
            putString(WhisperProcessingService.KEY_VIDEO_URI, videoUri.toString())
            putString(WhisperProcessingService.KEY_BASE_NAME, baseName)
        }
        try {
            target.send(Message.obtain(null, WhisperProcessingService.MSG_START).apply {
                this.data = data
                replyTo = incomingMessenger
            })
        } catch (_: RemoteException) {
            fail("字幕処理サービスとの通信が切断されました。")
        }
    }

    private fun loadResultAndPlay() {
        runCatching {
            val result = File(filesDir, "current/translated.srt")
            require(result.isFile && result.length() > 0L) { "翻訳済み字幕ファイルがありません" }
            SrtCodec.parse(result.readText(Charsets.UTF_8))
        }.onSuccess { entries ->
            setProgress(100, "字幕の準備が完了しました。")
            showPlayer(videoUri, entries)
        }.onFailure { error ->
            fail("字幕結果を読み込めませんでした: ${error.message ?: error.javaClass.simpleName}")
        }
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
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(statusText)
        root.addView(progress)
        root.addView(TextView(this).apply {
            text = "Android音声認識を優先し、利用できない場合はWhisperへ自動で切り替えます。"
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
        root.addView(
            subtitleView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                this.bottomMargin = bottomMargin
            },
        )
        val editButton = Button(this).apply {
            text = "保存・編集"
            isAllCaps = false
            setOnClickListener {
                player?.pause()
                startActivity(Intent(this@SharedPlaybackActivity, MainActivity::class.java))
            }
        }
        root.addView(
            editButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12)
                marginEnd = dp(12)
            },
        )
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
