package dev.oai.subtitlevideo.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtCodecTest {
    @Test
    fun mergeTranslationPreservesSourceTimingAndUsesTranslatedText() {
        val source = listOf(
            SubtitleEntry(1, 0, 2500, "你好"),
            SubtitleEntry(2, 3000, 6000, "再见"),
        )
        val translated = """
            1
            00:00:10,000 --> 00:00:11,000
            こんにちは

            2
            00:00:20,000 --> 00:00:21,000
            またね
        """.trimIndent()

        val merged = SrtCodec.mergeTranslation(source, translated)

        assertEquals(0, merged[0].startMs)
        assertEquals(2500, merged[0].endMs)
        assertEquals("こんにちは", merged[0].text)
        assertEquals(3000, merged[1].startMs)
        assertEquals(6000, merged[1].endMs)
        assertEquals("またね", merged[1].text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mergeTranslationRejectsDuplicateIds() {
        val source = listOf(
            SubtitleEntry(1, 0, 1000, "a"),
            SubtitleEntry(2, 1000, 2000, "b"),
        )
        val translated = """
            1
            00:00:00,000 --> 00:00:01,000
            A

            1
            00:00:01,000 --> 00:00:02,000
            B
        """.trimIndent()

        SrtCodec.mergeTranslation(source, translated)
    }

    @Test
    fun displayTimelinePreservesOverallTimingAndSplitsLongText() {
        val source = listOf(
            SubtitleEntry(
                1,
                0,
                12_000,
                "これはかなり長い字幕なので、一度に表示せず読みやすい長さへ分割して表示する必要があります。さらに文章を追加して十分長くします。",
            )
        )

        val timeline = SubtitleChunker.toDisplayTimeline(source)

        assertTrue(timeline.size >= 2)
        assertEquals(0, timeline.first().startMs)
        assertEquals(12_000, timeline.last().endMs)
        timeline.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMs, right.startMs)
        }
        assertTrue(timeline.all { it.text.length <= 42 })
    }
}
