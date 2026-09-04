package dev.oai.subtitlevideo.whisper

import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.Closeable
import java.io.File

class WhisperEngine(modelFile: File) : Closeable {
    private var handle: Long = WhisperNative.nativeInit(modelFile.absolutePath)

    fun transcribe(samples: FloatArray, chunkStartMs: Long, language: String = "zh"): List<SubtitleEntry> {
        check(handle != 0L) { "Whisperは既に終了しています" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val result = WhisperNative.nativeTranscribe(handle, samples, language, threads)
        return result.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2].trim()
            if (text.isEmpty()) return@mapNotNull null
            SubtitleEntry(
                index = 0,
                startMs = chunkStartMs + start,
                endMs = chunkStartMs + end,
                text = text,
            )
        }.toList()
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0
        }
    }
}
