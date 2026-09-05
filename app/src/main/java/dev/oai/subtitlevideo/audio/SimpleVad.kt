package dev.oai.subtitlevideo.audio

import kotlin.math.sqrt

/**
 * Lightweight local VAD used only as an optional long-video speed optimization.
 * It keeps original sample offsets so subtitle timestamps stay on the source timeline.
 */
object SimpleVad {
    data class SpeechWindow(val samples: FloatArray, val offsetMs: Long)

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 20
    private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    private const val SILENCE_TO_CLOSE_MS = 300
    private const val PADDING_MS = 250
    private const val MERGE_GAP_MS = 750
    private const val MIN_SPEECH_MS = 80

    fun split(samples: FloatArray): List<SpeechWindow> {
        if (samples.size < FRAME_SAMPLES * 5) return listOf(SpeechWindow(samples, 0))
        val rms = buildList {
            var start = 0
            while (start + FRAME_SAMPLES <= samples.size) {
                var sum = 0.0
                for (i in start until start + FRAME_SAMPLES) {
                    val v = samples[i].toDouble()
                    sum += v * v
                }
                add(sqrt(sum / FRAME_SAMPLES))
                start += FRAME_SAMPLES
            }
        }
        if (rms.isEmpty()) return listOf(SpeechWindow(samples, 0))

        val sorted = rms.sorted()
        val noiseFloor = sorted[(sorted.lastIndex * 15 / 100).coerceIn(0, sorted.lastIndex)]
        val threshold = maxOf(0.008, noiseFloor * 3.0)
        val closeFrames = (SILENCE_TO_CLOSE_MS / FRAME_MS).coerceAtLeast(1)
        val minFrames = (MIN_SPEECH_MS / FRAME_MS).coerceAtLeast(1)

        val raw = mutableListOf<IntRange>()
        var speechStart = -1
        var silentFrames = 0
        rms.forEachIndexed { index, value ->
            if (value >= threshold) {
                if (speechStart < 0) speechStart = index
                silentFrames = 0
            } else if (speechStart >= 0) {
                silentFrames++
                if (silentFrames >= closeFrames) {
                    val end = index - silentFrames
                    if (end - speechStart + 1 >= minFrames) raw += speechStart..end
                    speechStart = -1
                    silentFrames = 0
                }
            }
        }
        if (speechStart >= 0) {
            val end = rms.lastIndex
            if (end - speechStart + 1 >= minFrames) raw += speechStart..end
        }
        if (raw.isEmpty()) return listOf(SpeechWindow(samples, 0))

        val paddingFrames = PADDING_MS / FRAME_MS
        val mergeFrames = MERGE_GAP_MS / FRAME_MS
        val padded = raw.map { range ->
            maxOf(0, range.first - paddingFrames)..minOf(rms.lastIndex, range.last + paddingFrames)
        }
        val merged = mutableListOf<IntRange>()
        padded.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first - previous.last <= mergeFrames) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged += range
            }
        }

        return merged.map { range ->
            val sampleStart = range.first * FRAME_SAMPLES
            val sampleEnd = minOf(samples.size, (range.last + 1) * FRAME_SAMPLES)
            SpeechWindow(
                samples = samples.copyOfRange(sampleStart, sampleEnd),
                offsetMs = sampleStart * 1000L / SAMPLE_RATE,
            )
        }
    }
}
