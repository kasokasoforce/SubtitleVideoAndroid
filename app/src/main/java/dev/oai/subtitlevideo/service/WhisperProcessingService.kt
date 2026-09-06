package dev.oai.subtitlevideo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import dev.oai.subtitlevideo.audio.AudioChunkDecoder
import dev.oai.subtitlevideo.model.WhisperModelManager
import dev.oai.subtitlevideo.model.WhisperModelSpec
import dev.oai.subtitlevideo.settings.AppSettings
import dev.oai.subtitlevideo.srt.SrtCodec
import dev.oai.subtitlevideo.srt.SubtitleEntry
import dev.oai.subtitlevideo.translation.LocalTranslator
import dev.oai.subtitlevideo.whisper.WhisperEngine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WhisperProcessingService : Service() {
    companion object {
        const val MSG_START = 1
        const val MSG_PROGRESS = 2
        const val MSG_DONE = 3
        const val MSG_ERROR = 4

        const val KEY_VIDEO_URI = "videoUri"
        const val KEY_BASE_NAME = "baseName"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"

        private const val PREFS = "current_project"
        private const val CHANNEL_ID = "shared_whisper_processing"
        private const val NOTIFICATION_ID = 2410
        // Match the fast v0.1 transcription shape: long chunks, one Whisper call for short videos.
        private const val WHISPER_CHUNK_SECONDS = 300
        private const val WHISPER_MAX_THREADS = 8
        private val PLAYBACK_MODEL = WhisperModelSpec.TINY_Q5
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var client: Messenger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastProgress = 0
    @Volatile private var lastMessage = "字幕処理を準備中"

    private val incomingMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == MSG_START) {
                client = message.replyTo
                updateProgress(lastProgress, lastMessage)
                if (running.compareAndSet(false, true)) {
                    val data = message.data
                    val uri = data.getString(KEY_VIDEO_URI)?.let(Uri::parse)
                    val baseName = data.getString(KEY_BASE_NAME).orEmpty().ifBlank { "shared_video" }
                    if (uri == null) {
                        running.set(false)
                        sendError("共有動画を開けませんでした。")
                    } else {
                        startProcessing(uri, baseName)
                    }
                }
                true
            } else false
        })
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "共有動画の字幕処理", NotificationManager.IMPORTANCE_LOW)
        )
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitleVideo:shared-whisper").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        startForeground(NOTIFICATION_ID, notification(lastMessage))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onDestroy() {
        worker.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startProcessing(videoUri: Uri, baseName: String) {
        worker.execute {
            runCatching {
                val settings = AppSettings.load(this)
                val source = transcribeWhisperDirect(videoUri, settings)
                saveSource(source)
                updateProgress(82, "字幕を${settings.targetLanguageLabel}へ翻訳します。")
                val translated = translate(source, settings)
                saveTranslated(translated)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("videoUri", videoUri.toString())
                    .putString("baseName", baseName)
                    .remove("outputUri")
                    .commit()
            }.onSuccess {
                updateProgress(100, "字幕の準備が完了しました。")
                sendSimple(MSG_DONE)
                finishWork()
            }.onFailure { error ->
                sendError("字幕付き再生の準備に失敗しました: ${error.message ?: error.javaClass.simpleName}")
                finishWork()
            }
        }
    }

    private fun transcribeWhisperDirect(uri: Uri, settings: AppSettings): List<SubtitleEntry> {
        val modelManager = WhisperModelManager(this)
        if (!modelManager.isReady(PLAYBACK_MODEL)) {
            updateProgress(5, "Whisperモデルを準備します（約32MB）")
            modelManager.download(PLAYBACK_MODEL, WhisperModelManager.DownloadControl()) { state ->
                updateProgress((state.percent * 15) / 100, "Whisperモデルを準備中: ${state.percent}%")
            }
        }
        updateProgress(16, "高速Whisper経路で字幕を作成します")
        return transcribeWhisper(uri, settings, modelManager)
    }

    private fun transcribeWhisper(
        uri: Uri,
        settings: AppSettings,
        modelManager: WhisperModelManager,
    ): List<SubtitleEntry> {
        val all = mutableListOf<SubtitleEntry>()
        WhisperEngine(modelManager.modelFile(PLAYBACK_MODEL)).use { whisper ->
            AudioChunkDecoder(this).decode(
                uri = uri,
                chunkSeconds = WHISPER_CHUNK_SECONDS,
                onProgress = { percent ->
                    updateProgress(16 + (percent * 10) / 100, "Whisper用音声を読み取り中: $percent%")
                },
            ) { samples, chunkStartMs ->
                val chunkNumber = (chunkStartMs / (WHISPER_CHUNK_SECONDS * 1000L)).toInt() + 1
                updateProgress(
                    (28 + (chunkNumber - 1) * 8).coerceAtMost(80),
                    "Whisperで字幕を作成中: ${formatPosition(chunkStartMs)}付近 / 区間 $chunkNumber",
                )
                all += whisper.transcribe(
                    samples = samples,
                    chunkStartMs = chunkStartMs,
                    language = settings.recognitionLanguageCode,
                    wordTiming = false,
                    maxThreads = WHISPER_MAX_THREADS,
                )
            }
        }
        return all
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .mapIndexed { index, item -> item.copy(index = index + 1) }
            .also { require(it.isNotEmpty()) { "字幕を認識できませんでした" } }
    }

    private fun translate(source: List<SubtitleEntry>, settings: AppSettings): List<SubtitleEntry> = try {
        LocalTranslator(settings.recognitionLanguageCode, settings.targetLanguageCode).use { translator ->
            translator.translate(source) { percent ->
                updateProgress(82 + (percent * 17) / 100, "${settings.targetLanguageLabel}字幕を準備中: $percent%")
            }
        }
    } catch (error: IllegalArgumentException) {
        if (error.message?.contains("元言語と翻訳先が同じ") == true) source else throw error
    }

    private fun projectDir() = File(filesDir, "current").apply { mkdirs() }
    private fun saveSource(entries: List<SubtitleEntry>) {
        File(projectDir(), "source.srt").writeText(SrtCodec.format(entries), Charsets.UTF_8)
    }
    private fun saveTranslated(entries: List<SubtitleEntry>) {
        File(projectDir(), "translated.srt").writeText(SrtCodec.format(entries), Charsets.UTF_8)
    }

    private fun updateProgress(value: Int, message: String) {
        lastProgress = maxOf(lastProgress, value.coerceIn(0, 100))
        lastMessage = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        send(MSG_PROGRESS, Bundle().apply {
            putInt(KEY_PROGRESS, lastProgress)
            putString(KEY_MESSAGE, message)
        })
    }

    private fun sendError(message: String) =
        send(MSG_ERROR, Bundle().apply { putString(KEY_MESSAGE, message) })

    private fun sendSimple(what: Int) = send(what, Bundle.EMPTY)

    private fun send(what: Int, data: Bundle) {
        val target = client ?: return
        try {
            target.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            client = null
        }
    }

    private fun finishWork() {
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("字幕動画メーカー")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun formatPosition(positionMs: Long): String {
        val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}
