package dev.oai.subtitlevideo.srt

object SrtCodec {
    private val blockRegex = Regex(
        pattern = """(?ms)^[ \t]*(\d+)[ \t]*\n[ \t]*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})[ \t]*-->[ \t]*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})[^\n]*\n(.*?)(?=\n[ \t]*\n[ \t]*\d+[ \t]*\n|\z)"""
    )

    fun parse(raw: String): List<SubtitleEntry> {
        val text = raw.removePrefix("\uFEFF")
            .replace("```srt", "", ignoreCase = true)
            .replace("```", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val entries = blockRegex.findAll(text).map { match ->
            val index = match.groupValues[1].toInt()
            val startMs = parseTime(match.groupValues[2])
            val endMs = parseTime(match.groupValues[3])
            require(endMs > startMs) { "字幕 $index の終了時刻が開始時刻以前です" }
            val body = match.groupValues[4]
                .lines()
                .joinToString("\n") { it.trim() }
                .trim()
            require(body.isNotEmpty()) { "字幕 $index が空です" }
            SubtitleEntry(index, startMs, endMs, body)
        }.toList()
        require(entries.isNotEmpty()) { "SRT字幕を読み取れませんでした" }
        return entries
    }

    fun format(entries: List<SubtitleEntry>): String = buildString {
        entries.forEachIndexed { i, entry ->
            append(entry.index.takeIf { it > 0 } ?: i + 1).append('\n')
            append(formatTime(entry.startMs)).append(" --> ").append(formatTime(entry.endMs)).append('\n')
            append(entry.text.trim()).append("\n\n")
        }
    }

    fun mergeTranslation(source: List<SubtitleEntry>, translatedRaw: String): List<SubtitleEntry> {
        val translated = parse(translatedRaw)
        val byId = translated.associateBy { it.index }
        val allIdsPresent = source.all { byId.containsKey(it.index) }
        require(allIdsPresent || source.size == translated.size) {
            "字幕数が一致しません。元=${source.size}, 翻訳=${translated.size}"
        }
        return source.mapIndexed { position, src ->
            val tr = if (allIdsPresent) byId.getValue(src.index) else translated[position]
            src.copy(text = tr.text.trim())
        }
    }

    private fun parseTime(value: String): Long {
        val normalized = value.replace('.', ',')
        val parts = normalized.split(':', ',')
        require(parts.size == 4) { "不正なタイムコード: $value" }
        val h = parts[0].toLong()
        val m = parts[1].toLong()
        val s = parts[2].toLong()
        val ms = parts[3].toLong()
        return (((h * 60 + m) * 60 + s) * 1000 + ms)
    }

    private fun formatTime(msValue: Long): String {
        var ms = msValue.coerceAtLeast(0)
        val h = ms / 3_600_000
        ms %= 3_600_000
        val m = ms / 60_000
        ms %= 60_000
        val s = ms / 1000
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }
}
