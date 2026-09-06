package dev.oai.subtitlevideo.asr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.oai.subtitlevideo.audio.AudioChunkDecoder
import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.FileOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Fast-path ASR using Android's installed speech recognition service.
 *
 * API 34+ can accept an already-opened PCM audio source and return word timestamps. This lets
 * us avoid running Whisper on the device when the installed recognition service supports it.
 * The implementation may use a network service depending on the device/recognizer provider.
 */
class SystemSpeechRecognizerTranscriber(private val context: Context) {
    companion object {
        private const val TIMEOUT_MINUTES = 8L

        fun isUsable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                SpeechRecognizer.isRecognitionAvailable(context)
    }

    private data class TimedPiece(val atMs: Long, val text: String)

    fun transcribe(
        uri: Uri,
        languageCode: String,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): List<SubtitleEntry> {
        check(Build.VERSION.SDK_INT >= 34) { "Android音声認識の動画入力にはAndroid 14以降が必要です" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Android音声認識を使う権限がありません"
        }
        check(SpeechRecognizer.isRecognitionAvailable(context)) { "Android音声認識サービスがありません" }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val pieces = LinkedHashMap<String, TimedPiece>()
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val feeder = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())

        fun addParts(bundle: Bundle?) {
            if (bundle == null || Build.VERSION.SDK_INT < 34) return
            val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
                ?: return
            synchronized(pieces) {
                parts.forEach { part ->
                    val text = part.rawText.trim()
                    val at = part.timestampMillis
                    if (text.isNotEmpty() && at >= 0L) {
                        pieces["$at\u0000$text"] = TimedPiece(at, text)
                    }
                }
            }
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onProgress(8, "Android音声認識へ動画の音声を送信中")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                failure.compareAndSet(null, IllegalStateException("Android音声認識エラー: $error"))
                latch.countDown()
            }

            override fun onResults(results: Bundle?) {
                addParts(results)
                latch.countDown()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                addParts(partialResults)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onSegmentResults(segmentResults: Bundle) {
                addParts(segmentResults)
                val count = synchronized(pieces) { pieces.size }
                onProgress(55, "Android音声認識中: $count 語/文字を取得")
            }

            override fun onEndOfSegmentedSession() {
                latch.countDown()
            }
        }

        try {
            main.post {
                runCatching {
                    SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
                        recognizerRef.set(recognizer)
                        recognizer.setRecognitionListener(listener)
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, toLocaleTag(languageCode))
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioChunkDecoder.TARGET_SAMPLE_RATE)
                            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                        }
                        recognizer.startListening(intent)
                    }
                }.onFailure {
                    failure.compareAndSet(null, it)
                    latch.countDown()
                }
            }

            feeder.execute {
                try {
                    FileOutputStream(writeSide.fileDescriptor).use { out ->
                        AudioChunkDecoder(context).decode(
                            uri = uri,
                            chunkSeconds = 5,
                            useProcessingGuard = false,
                            onProgress = { percent ->
                                onProgress((8 + percent * 32 / 100).coerceAtMost(40), "動画音声を送信中: $percent%")
                            },
                        ) { samples, _ ->
                            val bytes = ByteArray(samples.size * 2)
                            var p = 0
                            samples.forEach { sample ->
                                val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()
                                bytes[p++] = (value and 0xff).toByte()
                                bytes[p++] = ((value ushr 8) and 0xff).toByte()
                            }
                            out.write(bytes)
                        }
                        out.flush()
                    }
                    onProgress(45, "音声送信完了。認識結果を待っています")
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    latch.countDown()
                    runCatching { writeSide.close() }
                }
            }

            if (!latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw IllegalStateException("Android音声認識がタイムアウトしました")
            }
            failure.get()?.let { throw it }

            val ordered = synchronized(pieces) { pieces.values.sortedBy { it.atMs } }
            require(ordered.isNotEmpty()) { "Android音声認識がタイムスタンプ付き結果を返しませんでした" }
            require(ordered.any { it.atMs > 0L } || ordered.size == 1) {
                "Android音声認識が単語タイムスタンプに対応していません"
            }
            onProgress(70, "Android音声認識完了: ${ordered.size} 語/文字")
            return group(ordered)
        } finally {
            feeder.shutdownNow()
            runCatching { writeSide.close() }
            runCatching { readSide.close() }
            val recognizer = recognizerRef.getAndSet(null)
            if (recognizer != null) {
                main.post { runCatching { recognizer.destroy() } }
            }
        }
    }

    private fun group(parts: List<TimedPiece>): List<SubtitleEntry> {
        val result = mutableListOf<SubtitleEntry>()
        var current = mutableListOf<TimedPiece>()

        fun textOf(items: List<TimedPiece>): String {
            val out = StringBuilder()
            items.forEach { item ->
                val piece = item.text.trim()
                if (piece.isEmpty()) return@forEach
                if (out.isNotEmpty() && needsSpace(out.last(), piece.first())) out.append(' ')
                out.append(piece)
            }
            return out.toString().trim()
        }

        fun flush(nextStart: Long?) {
            if (current.isEmpty()) return
            val text = textOf(current)
            if (text.isNotEmpty()) {
                val start = current.first().atMs
                val naturalEnd = current.last().atMs + 1_200L
                val end = when {
                    nextStart != null && nextStart > start -> minOf(naturalEnd, nextStart)
                    else -> naturalEnd
                }.coerceAtLeast(start + 350L)
                result += SubtitleEntry(0, start, end, text)
            }
            current = mutableListOf()
        }

        parts.forEachIndexed { index, part ->
            if (current.isNotEmpty()) {
                val currentText = textOf(current)
                val gap = part.atMs - current.last().atMs
                val duration = part.atMs - current.first().atMs
                val punctuationBreak = currentText.lastOrNull() in setOf('。', '！', '？', '!', '?', '；', ';')
                if (gap >= 1_200L || duration >= 4_500L || currentText.length >= 34 || (punctuationBreak && currentText.length >= 8)) {
                    flush(part.atMs)
                }
            }
            current += part
            if (index == parts.lastIndex) flush(null)
        }

        return result.mapIndexed { index, entry -> entry.copy(index = index + 1) }
    }

    private fun needsSpace(left: Char, right: Char): Boolean =
        left.code < 128 && right.code < 128 && left.isLetterOrDigit() && right.isLetterOrDigit()

    private fun toLocaleTag(code: String): String = when (code.lowercase()) {
        "zh" -> "zh-CN"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "pt" -> "pt-BR"
        "ru" -> "ru-RU"
        "ar" -> "ar-SA"
        "hi" -> "hi-IN"
        "vi" -> "vi-VN"
        "auto" -> "zh-CN"
        else -> code
    }
}
