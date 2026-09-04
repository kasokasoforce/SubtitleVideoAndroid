package dev.oai.subtitlevideo.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class WhisperModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE = "ggml-small.bin"
        private const val EXPECTED_SHA256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-small.bin?download=true"
    }

    val modelFile: File get() = File(context.filesDir, "models/$MODEL_FILE")
    private val verificationMarker: File get() = File(context.filesDir, "models/$MODEL_FILE.sha256")

    fun isReady(): Boolean = modelFile.isFile && verificationMarker.readTextOrNull()?.trim()
        ?.equals(EXPECTED_SHA256, ignoreCase = true) == true

    fun download(onProgress: (Int) -> Unit) {
        val target = modelFile
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$MODEL_FILE.part")
        if (temp.exists()) temp.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "モデル取得HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            val actual = sha256(temp)
            require(actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
                "WhisperモデルのSHA-256が一致しません: $actual"
            }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Whisperモデルを保存できませんでした" }
            verificationMarker.writeText(EXPECTED_SHA256, Charsets.US_ASCII)
            onProgress(100)
        } finally {
            connection.disconnect()
            if (temp.exists() && !target.exists()) temp.delete()
        }
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
