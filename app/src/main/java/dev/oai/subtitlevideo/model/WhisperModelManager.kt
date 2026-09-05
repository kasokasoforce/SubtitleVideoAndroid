package dev.oai.subtitlevideo.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class WhisperModelManager(private val context: Context) {
    data class DownloadProgress(
        val percent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    )

    class DownloadControl {
        private val paused = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        fun pause() = paused.set(true)
        fun resume() = paused.set(false)
        fun cancel() { cancelled.set(true); paused.set(false) }
        fun isPaused(): Boolean = paused.get()
        internal fun awaitIfPaused() {
            while (paused.get() && !cancelled.get()) Thread.sleep(150)
            if (cancelled.get()) throw InterruptedException("ダウンロードをキャンセルしました")
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
    }

    fun modelFile(spec: WhisperModelSpec): File = File(context.filesDir, "models/${spec.fileName}")
    private fun partialFile(spec: WhisperModelSpec): File = File(context.filesDir, "models/${spec.fileName}.part")
    private fun verificationMarker(spec: WhisperModelSpec): File = File(context.filesDir, "models/${spec.fileName}.sha256")

    fun isReady(spec: WhisperModelSpec): Boolean {
        val file = modelFile(spec)
        return file.isFile && file.length() == spec.expectedSizeBytes &&
            verificationMarker(spec).readTextOrNull()?.trim()?.equals(spec.sha256, ignoreCase = true) == true
    }

    fun downloadedBytes(spec: WhisperModelSpec): Long = when {
        modelFile(spec).exists() -> modelFile(spec).length()
        partialFile(spec).exists() -> partialFile(spec).length()
        else -> 0L
    }

    fun delete(spec: WhisperModelSpec) {
        modelFile(spec).delete()
        partialFile(spec).delete()
        verificationMarker(spec).delete()
    }

    fun download(
        spec: WhisperModelSpec,
        control: DownloadControl = DownloadControl(),
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val target = modelFile(spec)
        target.parentFile?.mkdirs()
        val temp = partialFile(spec)

        if (temp.exists() && temp.length() > spec.expectedSizeBytes) temp.delete()
        if (temp.length() == spec.expectedSizeBytes) {
            if (sha256(temp).equals(spec.sha256, ignoreCase = true)) {
                installVerified(spec, temp, target)
                onProgress(DownloadProgress(100, spec.expectedSizeBytes, spec.expectedSizeBytes, 0))
                return
            }
            temp.delete()
        }

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                control.awaitIfPaused()
                downloadAttempt(spec, temp, control, onProgress)
                require(temp.length() == spec.expectedSizeBytes) {
                    "モデルサイズが不完全です: ${temp.length()} / ${spec.expectedSizeBytes} bytes"
                }
                val actual = sha256(temp)
                require(actual.equals(spec.sha256, ignoreCase = true)) {
                    "WhisperモデルのSHA-256が一致しません: $actual"
                }
                installVerified(spec, temp, target)
                onProgress(DownloadProgress(100, spec.expectedSizeBytes, spec.expectedSizeBytes, 0))
                return
            } catch (error: Throwable) {
                lastError = error
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                if (temp.length() > spec.expectedSizeBytes || error.message?.contains("SHA-256") == true) temp.delete()
                if (attempt < MAX_ATTEMPTS - 1) {
                    var waited = 0L
                    val waitMs = longArrayOf(1_000L, 3_000L, 7_000L)[attempt]
                    while (waited < waitMs) {
                        control.awaitIfPaused()
                        Thread.sleep(250)
                        waited += 250
                    }
                }
            }
        }
        throw IOException("Whisperモデルのダウンロードに失敗しました: ${lastError?.message ?: "通信エラー"}", lastError)
    }

    private fun downloadAttempt(
        spec: WhisperModelSpec,
        temp: File,
        control: DownloadControl,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var resumeFrom = temp.length().coerceIn(0L, spec.expectedSizeBytes)
        val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            val append = when {
                code == HttpURLConnection.HTTP_PARTIAL -> true
                code in 200..299 -> {
                    if (resumeFrom > 0L) {
                        resumeFrom = 0L
                        temp.delete()
                    }
                    false
                }
                code == 416 && temp.length() == spec.expectedSizeBytes -> return
                code == 416 -> {
                    temp.delete()
                    throw IOException("モデル再開位置が無効です (HTTP 416)")
                }
                else -> throw IOException("モデル取得HTTP $code")
            }

            var lastBytes = resumeFrom
            var lastTime = System.nanoTime()
            emitProgress(spec, resumeFrom, 0, onProgress)
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = resumeFrom
                    while (true) {
                        control.awaitIfPaused()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done > spec.expectedSizeBytes) throw IOException("モデル受信サイズが想定値を超えました")

                        val now = System.nanoTime()
                        val elapsed = now - lastTime
                        if (elapsed >= 500_000_000L || done == spec.expectedSizeBytes) {
                            val speed = if (elapsed > 0) ((done - lastBytes) * 1_000_000_000L / elapsed) else 0
                            emitProgress(spec, done, speed, onProgress)
                            lastBytes = done
                            lastTime = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun emitProgress(spec: WhisperModelSpec, done: Long, speed: Long, callback: (DownloadProgress) -> Unit) {
        callback(
            DownloadProgress(
                percent = ((done.coerceIn(0L, spec.expectedSizeBytes) * 100L) / spec.expectedSizeBytes).toInt(),
                downloadedBytes = done,
                totalBytes = spec.expectedSizeBytes,
                bytesPerSecond = speed.coerceAtLeast(0),
            )
        )
    }

    private fun installVerified(spec: WhisperModelSpec, temp: File, target: File) {
        if (target.exists()) target.delete()
        require(temp.renameTo(target)) { "Whisperモデルを保存できませんでした" }
        verificationMarker(spec).writeText(spec.sha256, Charsets.US_ASCII)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText(Charsets.US_ASCII) }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
