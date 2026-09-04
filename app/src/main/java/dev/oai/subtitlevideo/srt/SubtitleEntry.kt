package dev.oai.subtitlevideo.srt

data class SubtitleEntry(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
