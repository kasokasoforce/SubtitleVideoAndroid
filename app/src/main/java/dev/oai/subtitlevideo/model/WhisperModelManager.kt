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
        const val MODEL_FILE = "ggml-small.bin"
        private const val EXPECTED_SHA256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"
        private const val EXPECTED_SIZE_BYTES = 487_601_967L
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-small.bin?download=true"
        private const val MAX_ATTEMPTS = 4
    }

    val modelFile: File get() = File(context.filesDir, "models/$MODEL_FILE")
    private val verificationMarker: File get() = File(context.filesDir, "models/$MODEL_FILE.sha256")

    fun isReady(): Boolean = modelFile.isFile && modelFile.length() == EXPECTED_SIZE_BYTES &&
        verificationMarker.readTextOrNull()?.trim()?.equals(EXPECTED_SHA256, ignoreCase = true) == true

    fun download(onProgress: (Int) -> Unit) {
        val target = modelFile
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$MODEL_FILE.part")

        if (temp.exists() && temp.length() > EXPECTED_SIZE_BYTES) temp.delete()
        if (temp.length() == EXPECTED_SIZE_BYTES) {
            if (sha256(temp).equals(EXPECTED_SHA256, ignoreCase = true)) {
                installVerified(temp, target)
                onProgress(100)
                return
            }
            temp.delete()
        }

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                downloadAttempt(temp, onProgress)
                require(temp.length() == EXPECTED_SIZE_BYTES) {
                    "モデルサイズが不完全です: ${temp.length()} / $EXPECTED_SIZE_BYTES bytes"
                }
                val actual = sha256(temp)
                require(actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
                    "WhisperモデルのSHA-256が一致しません: $actual"
                }
                installVerified(temp, target)
                onProgress(100)
                return
            } catch (error: Throwable) {
                lastError = error
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error

                // SHA不一致やサイズ超過は壊れた部分ファイルを再利用しない。
                if (temp.length() > EXPECTED_SIZE_BYTES || error.message?.contains("SHA-256") == true) {
                    temp.delete()
                }

                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(longArrayOf(1_000L, 3_000L, 7_000L)[attempt])
                }
            }
        }
        throw IOException("Whisperモデルのダウンロードに失敗しました: ${lastError?.message ?: "通信エラー"}", lastError)
    }

    private fun downloadAttempt(temp: File, onProgress: (Int) -> Unit) {
        var resumeFrom = temp.length().coerceIn(0L, EXPECTED_SIZE_BYTES)
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
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
                    // Rangeを無視して200が返った場合は先頭から取り直す。
                    if (resumeFrom > 0L) {
                        resumeFrom = 0L
                        temp.delete()
                    }
                    false
                }
                code == 416 && temp.length() == EXPECTED_SIZE_BYTES -> return
                code == 416 -> {
                    temp.delete()
                    throw IOException("モデル再開位置が無効です (HTTP 416)")
                }
                else -> throw IOException("モデル取得HTTP $code")
            }

            onProgress(percentOf(resumeFrom))
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = resumeFrom
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done > EXPECTED_SIZE_BYTES) {
                            throw IOException("モデル受信サイズが想定値を超えました")
                        }
                        onProgress(percentOf(done))
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun percentOf(bytes: Long): Int =
        ((bytes.coerceIn(0L, EXPECTED_SIZE_BYTES) * 100L) / EXPECTED_SIZE_BYTES).toInt().coerceIn(0, 100)

    private fun installVerified(temp: File, target: File) {
        if (target.exists()) target.delete()
        require(temp.renameTo(target)) { "Whisperモデルを保存できませんでした" }
        verificationMarker.writeText(EXPECTED_SHA256, Charsets.US_ASCII)
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
