package dev.oai.subtitlevideo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.oai.subtitlevideo.service.ProcessingGuardService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Receives a video from Android's share sheet (Telegram/Goreglam etc.) and makes a stable,
 * app-private working copy. This avoids depending on the sender's temporary content:// grant
 * during a long Whisper/export job. No manual "save video" step is required.
 */
class VideoShareActivity : Activity() {
    companion object {
        private const val PREFS = "current_project"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val sharedUri = extractSharedUri(intent)
        if (intent?.action != Intent.ACTION_SEND || sharedUri == null || !looksLikeVideo(sharedUri, intent?.type)) {
            fail("共有された動画を取得できませんでした。")
            return
        }
        importSharedVideo(sharedUri)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "共有動画を取り込み中"
            textSize = 22f
        })
        status = TextView(this).apply {
            text = "Goreglam / Telegramから受け取った動画を準備しています…"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(14))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        root.addView(status)
        root.addView(progress)
        setContentView(root)
    }

    private fun importSharedVideo(source: Uri) {
        ProcessingGuardService.start(this, "共有動画を取り込み中")
        worker.execute {
            runCatching {
                val displayName = queryDisplayName(source)
                val expectedSize = querySize(source)
                val extension = displayName.substringAfterLast('.', "mp4")
                    .lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    ?: "mp4"
                val projectDir = File(filesDir, "current").apply { mkdirs() }
                val target = File(projectDir, "shared_input.$extension")
                val temp = File(projectDir, "shared_input.$extension.part")

                projectDir.listFiles()
                    ?.filter { it.name.startsWith("shared_input.") && it != temp }
                    ?.forEach { it.delete() }
                if (temp.exists()) temp.delete()

                contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (expectedSize > 0L) {
                                val percent = ((copied * 100L) / expectedSize).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    progress.isIndeterminate = false
                                    progress.progress = percent
                                    status.text = "取り込み中: $percent%  ${formatMb(copied)} / ${formatMb(expectedSize)} MB"
                                }
                            } else {
                                runOnUiThread { status.text = "取り込み中: ${formatMb(copied)} MB" }
                            }
                        }
                        output.fd.sync()
                    }
                } ?: error("共有動画を開けませんでした")

                require(temp.isFile && temp.length() > 0L) { "共有動画の内容が空です" }
                if (target.exists()) target.delete()
                require(temp.renameTo(target)) { "共有動画を作業領域へ保存できませんでした" }

                val stableUri = FileProvider.getUriForFile(this, "$packageName.files", target)
                val baseName = displayName.substringBeforeLast('.').ifBlank { "shared_video" }

                File(projectDir, "source.srt").delete()
                File(projectDir, "translated.srt").delete()
                File(projectDir, "source.zh.srt").delete()
                File(projectDir, "jp.srt").delete()

                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("videoUri", stableUri.toString())
                    .putString("baseName", baseName)
                    .remove("outputUri")
                    .apply()
                baseName
            }.onSuccess { name ->
                runOnUiThread {
                    ProcessingGuardService.stop(this)
                    progress.isIndeterminate = false
                    progress.progress = 100
                    status.text = "取り込み完了: $name\n字幕動画メーカーを開きます。"
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }.onFailure { error ->
                runOnUiThread {
                    ProcessingGuardService.stop(this)
                    fail("動画の取り込みに失敗しました: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val stream = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun looksLikeVideo(uri: Uri, intentType: String?): Boolean {
        val type = intentType ?: contentResolver.getType(uri)
        if (type?.startsWith("video/", ignoreCase = true) == true) return true
        val name = queryDisplayName(uri).lowercase()
        return listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".3gp").any(name::endsWith)
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "shared_video.mp4"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "shared_video.mp4"
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index).coerceAtLeast(0L)
            }
        }
        return 0L
    }

    private fun formatMb(bytes: Long): String = "%.1f".format(bytes / 1_000_000.0)

    private fun fail(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }
}
