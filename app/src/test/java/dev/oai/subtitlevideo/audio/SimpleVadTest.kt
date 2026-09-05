package dev.oai.subtitlevideo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleVadTest {
    @Test
    fun silenceAroundSpeechKeepsOriginalOffset() {
        val sampleRate = 16_000
        val samples = FloatArray(sampleRate * 3)
        // 1.0s - 2.0s speech-like waveform.
        for (i in sampleRate until sampleRate * 2) {
            samples[i] = if ((i and 1) == 0) 0.2f else -0.2f
        }

        val windows = SimpleVad.split(samples)
        assertTrue(windows.isNotEmpty())
        val first = windows.first()
        // 250 ms padding around detected speech means roughly 750 ms start.
        assertTrue(first.offsetMs in 700L..850L)
        assertTrue(first.samples.size > sampleRate)
        assertTrue(first.samples.size < samples.size)
    }

    @Test
    fun allSilenceFallsBackToOriginalAudio() {
        val samples = FloatArray(16_000)
        val windows = SimpleVad.split(samples)
        assertEquals(1, windows.size)
        assertEquals(0L, windows.single().offsetMs)
        assertEquals(samples.size, windows.single().samples.size)
    }
}
