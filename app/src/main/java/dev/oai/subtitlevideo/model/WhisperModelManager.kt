package dev.oai.subtitlevideo.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class WhisperModelManager(private val context: Context) {
    companion object {
        private const val MAX_ATTEMPTS = 4
    }

    fun modelFile(spec: WhisperModelSpec): File = File(context.filesDir, "models/${spec.fileName}")
    private fun verificationMarker(spec: WhisperModelSpec): File =
        File(context.filesDir, "models/${spec.fileName}.sha256")

    fun isReady(spec: WhisperModelSpec): Boolean {
        val file = modelFile(spec)
        return file.isFile && file.length() == spec.expectedSizeBytes &&
            verificationMarker(spec).readTextOrNull()?.trim()?.equals(spec.sha256, ignoreCase = true) == true
    }

    fun download(spec: WhisperModelSpec, onProgress: (Int) -> Unit) {
        val target = modelFile(spec)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${spec.fileName}.part")

        if (temp.exists() && temp.length() > spec.expectedSizeBytes) temp.delete()
        if (temp.length() == spec.expectedSizeBytes) {
            if (sha256(temp).equals(spec.sha256, ignoreCase = true)) {
                installVerified(spec, temp, target)
                onProgress(100)
                return
            }
            temp.delete()
        }

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                downloadAttempt(spec, temp, onProgress)
                require(temp.length() == spec.expectedSizeBytes) {
                    "モデルサイズが不完全です: ${temp.length()} / ${spec.expectedSizeBytes} bytes"
                }
                val actual = sha256(temp)
                require(actual.equals(spec.sha256, ignoreCase = true)) {
                    "WhisperモデルのSHA-256が一致しません: $actual"
                }
                installVerified(spec, temp, target)
                onProgress(100)
                return
            } catch (error: Throwable) {
                lastError = error
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                if (temp.length() > spec.expectedSizeBytes || error.message?.contains("SHA-256") == true) {
                    temp.delete()
                }
                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(longArrayOf(1_000L, 3_000L, 7_000L)[attempt])
                }
            }
        }
        throw IOException("Whisperモデルのダウンロードに失敗しました: ${lastError?.message ?: "通信エラー"}", lastError)
    }

    private fun downloadAttempt(spec: WhisperModelSpec, temp: File, onProgress: (Int) -> Unit) {
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

            onProgress(percentOf(resumeFrom, spec.expectedSizeBytes))
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = resumeFrom
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done > spec.expectedSizeBytes) throw IOException("モデル受信サイズが想定値を超えました")
                        onProgress(percentOf(done, spec.expectedSizeBytes))
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun percentOf(bytes: Long, total: Long): Int =
        ((bytes.coerceIn(0L, total) * 100L) / total).toInt().coerceIn(0, 100)

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
