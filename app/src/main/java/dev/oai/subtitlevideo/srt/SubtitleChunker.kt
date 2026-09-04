package dev.oai.subtitlevideo.srt

import kotlin.math.ceil

data class DisplaySubtitle(val startMs: Long, val endMs: Long, val text: String)

object SubtitleChunker {
    private const val BREAK_CHARS = "。！？!?、，,・…：:；; 」』）)]　 "
    private const val STRONG_BREAK_CHARS = "。！？!?"

    fun toDisplayTimeline(
        subtitles: List<SubtitleEntry>,
        maxEventChars: Int = 42,
        maxLineChars: Int = 24,
        maxEventSeconds: Double = 4.0,
    ): List<DisplaySubtitle> {
        return subtitles.flatMap { sub ->
            val chunks = chunkForDisplay(
                sub.text,
                (sub.endMs - sub.startMs) / 1000.0,
                maxEventChars,
                maxLineChars,
                maxEventSeconds,
            )
            weightedTimings(sub.startMs, sub.endMs, chunks).mapIndexed { index, timing ->
                DisplaySubtitle(timing.first, timing.second, chunks[index])
            }
        }
    }

    fun wrapLines(text: String, maxChars: Int = 24): List<String> = splitLongPiece(cleanText(text), maxChars)

    private fun cleanText(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("[ \\t\\u3000]*\\n[ \\t\\u3000]*"), " ")
        .replace(Regex("[ \\t\\u3000]+"), " ")
        .trim()

    private fun chooseBreak(text: String, maxChars: Int): Int {
        if (text.length <= maxChars) return text.length
        val start = maxOf(1, maxChars - 10)
        val end = minOf(text.length - 1, maxChars + 6)
        var best = -1
        var bestScore = -1
        for (i in start..end) {
            val left = text[i - 1]
            val right = text[i]
            val score = when {
                left in STRONG_BREAK_CHARS -> 3
                left in BREAK_CHARS -> 2
                right in BREAK_CHARS -> 1
                else -> -1
            }
            if (score >= bestScore) {
                best = i
                bestScore = score
            }
        }
        return if (best > 0) best else maxChars
    }

    private fun splitLongPiece(value: String, maxChars: Int): List<String> {
        var text = value.trim()
        if (text.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        while (text.length > maxChars) {
            val pos = chooseBreak(text, maxChars)
            val left = text.substring(0, pos).trim()
            val right = text.substring(pos).trim()
            if (left.isEmpty() || right.isEmpty()) break
            parts += left
            text = right
        }
        if (text.isNotEmpty()) parts += text
        return parts
    }

    private fun splitUnits(text: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { i, ch ->
            if (ch in STRONG_BREAK_CHARS) {
                val piece = text.substring(start, i + 1).trim()
                if (piece.isNotEmpty()) result += splitLongPiece(piece, maxChars)
                start = i + 1
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) result += splitLongPiece(tail, maxChars)
        return result
    }

    private fun chunkForDisplay(
        raw: String,
        durationSeconds: Double,
        maxEventCharsRaw: Int,
        maxLineCharsRaw: Int,
        maxEventSecondsRaw: Double,
    ): List<String> {
        val text = cleanText(raw)
        if (text.isEmpty()) return emptyList()
        val maxEventChars = maxOf(8, maxEventCharsRaw)
        val maxLineChars = maxOf(8, maxLineCharsRaw)
        val maxEventSeconds = maxOf(1.0, maxEventSecondsRaw)
        val byLength = maxOf(1, ceil(text.length.toDouble() / maxEventChars).toInt())
        val byTime = if (text.length > maxLineChars) maxOf(1, ceil(durationSeconds / maxEventSeconds).toInt()) else 1
        val targetChunks = maxOf(byLength, byTime)
        val targetChars = minOf(maxEventChars, maxOf(12, ceil(text.length.toDouble() / targetChunks).toInt()))

        val units = splitUnits(text, targetChars)
        val chunks = mutableListOf<String>()
        var current = ""
        for (unit in units) {
            val candidate = if (current.isEmpty()) unit else current + unit
            if (current.isNotEmpty() && candidate.length > targetChars) {
                chunks += current.trim()
                current = unit
            } else {
                current = candidate
            }
        }
        if (current.isNotBlank()) chunks += current.trim()
        return chunks.flatMap { splitLongPiece(it, maxEventChars) }
    }

    private fun weightedTimings(startMs: Long, endMs: Long, chunks: List<String>): List<Pair<Long, Long>> {
        if (chunks.isEmpty()) return emptyList()
        val total = (endMs - startMs).coerceAtLeast(1L)
        val weights = chunks.map { maxOf(1, it.replace(Regex("\\s+"), "").length) }
        val sum = weights.sum().toDouble()
        val boundaries = LongArray(chunks.size + 1)
        boundaries[0] = startMs
        boundaries[chunks.size] = endMs
        var cumulative = 0
        for (i in 1 until chunks.size) {
            cumulative += weights[i - 1]
            val ideal = startMs + (total * (cumulative / sum)).toLong()
            val remaining = chunks.size - i
            val earliest = boundaries[i - 1] + 1
            val latest = endMs - remaining
            boundaries[i] = if (earliest <= latest) ideal.coerceIn(earliest, latest) else ideal.coerceIn(startMs, endMs)
        }
        return chunks.indices.map { index -> boundaries[index] to boundaries[index + 1] }
    }
}
