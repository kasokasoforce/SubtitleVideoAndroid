package dev.oai.subtitlevideo.whisper

import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.Closeable
import java.io.File

class WhisperEngine(modelFile: File) : Closeable {
    private data class TimedToken(val startMs: Long, val endMs: Long, val text: String)

    private var handle: Long = WhisperNative.nativeInit(modelFile.absolutePath)

    fun transcribe(
        samples: FloatArray,
        chunkStartMs: Long,
        language: String = "zh",
        wordTiming: Boolean = false,
        maxThreads: Int = 8,
    ): List<SubtitleEntry> {
        check(handle != 0L) { "Whisperは既に終了しています" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, maxThreads.coerceAtLeast(2))
        if (wordTiming) {
            val raw = WhisperNative.nativeTranscribeWords(handle, samples, language, threads)
            val tokens = parseTokens(raw)
            if (tokens.isNotEmpty()) return groupTokens(tokens, chunkStartMs)
        }
        return parseSegments(WhisperNative.nativeTranscribe(handle, samples, language, threads), chunkStartMs)
    }

    private fun parseSegments(result: String, chunkStartMs: Long): List<SubtitleEntry> =
        result.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2].trim()
            if (text.isEmpty() || end <= start) return@mapNotNull null
            SubtitleEntry(0, chunkStartMs + start, chunkStartMs + end, text)
        }.toList()

    private fun parseTokens(result: String): List<TimedToken> =
        result.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2]
            if (text.isBlank() || end <= start) return@mapNotNull null
            TimedToken(start, end, text)
        }.toList()

    private fun groupTokens(tokens: List<TimedToken>, chunkStartMs: Long): List<SubtitleEntry> {
        val result = mutableListOf<SubtitleEntry>()
        var group = mutableListOf<TimedToken>()

        fun flush() {
            if (group.isEmpty()) return
            val text = joinTokens(group).trim()
            if (text.isNotEmpty()) {
                result += SubtitleEntry(
                    index = 0,
                    startMs = chunkStartMs + group.first().startMs,
                    endMs = chunkStartMs + group.last().endMs,
                    text = text,
                )
            }
            group = mutableListOf()
        }

        tokens.forEach { token ->
            val currentText = if (group.isEmpty()) "" else joinTokens(group)
            val gap = if (group.isEmpty()) 0L else token.startMs - group.last().endMs
            val duration = if (group.isEmpty()) 0L else token.endMs - group.first().startMs
            val strongBreak = currentText.trimEnd().lastOrNull() in setOf('。', '！', '？', '!', '?')
            val shouldBreak = group.isNotEmpty() && (
                gap >= 1_200L ||
                    currentText.length >= 42 ||
                    duration >= 4_000L ||
                    (strongBreak && currentText.length >= 12)
                )
            if (shouldBreak) flush()
            group += token
        }
        flush()
        return result
    }

    private fun joinTokens(tokens: List<TimedToken>): String {
        val out = StringBuilder()
        tokens.forEach { token ->
            val piece = token.text
            if (out.isNotEmpty() && needsSpace(out.last(), piece.firstOrNull())) out.append(' ')
            out.append(piece.trim())
        }
        return out.toString()
    }

    private fun needsSpace(left: Char, right: Char?): Boolean {
        if (right == null || left.isWhitespace() || right.isWhitespace()) return false
        val leftAsciiWord = left.isLetterOrDigit() && left.code < 128
        val rightAsciiWord = right.isLetterOrDigit() && right.code < 128
        return leftAsciiWord && rightAsciiWord
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0
        }
    }
}
