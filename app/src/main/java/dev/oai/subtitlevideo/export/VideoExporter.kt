package dev.oai.subtitlevideo.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dev.oai.subtitlevideo.srt.SubtitleChunker
import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.File

@UnstableApi
class VideoExporter(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var progressRunnable: Runnable? = null

    fun export(
        input: Uri,
        subtitles: List<SubtitleEntry>,
        baseName: String,
        onProgress: (Int) -> Unit,
        onCompleted: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(transformer == null) { "動画出力は既に実行中です" }
        val timeline = SubtitleChunker.toDisplayTimeline(subtitles)
        require(timeline.isNotEmpty()) { "表示可能な字幕がありません" }
        val overlay = SubtitleCanvasOverlay(timeline)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()

        val temp = File(context.cacheDir, "${baseName}_captioned.mp4")
        if (temp.exists()) temp.delete()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                stopProgressPolling()
                transformer = null
                Thread {
                    runCatching { publishVideo(temp, "${baseName}_captioned.mp4") }
                        .onSuccess { uri -> mainHandler.post { onCompleted(uri) } }
                        .onFailure { error -> mainHandler.post { onError(error) } }
                }.start()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                stopProgressPolling()
                transformer = null
                onError(exportException)
            }
        }

        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener)
            .build()
        transformer!!.start(edited, temp.absolutePath)
        startProgressPolling(onProgress)
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        stopProgressPolling()
    }

    private fun startProgressPolling(onProgress: (Int) -> Unit) {
        val holder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                val state = current.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress.coerceIn(0, 100))
                }
                mainHandler.postDelayed(this, 500)
            }
        }
        progressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let(mainHandler::removeCallbacks)
        progressRunnable = null
    }

    private fun publishVideo(temp: File, displayName: String): Uri {
        require(temp.isFile && temp.length() > 0) { "出力動画が作成されませんでした" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SubtitleVideo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStoreへ動画を作成できません")
            try {
                resolver.openOutputStream(uri, "w")!!.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                temp.delete()
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val target = File(dir, displayName)
        temp.copyTo(target, overwrite = true)
        temp.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }
}
