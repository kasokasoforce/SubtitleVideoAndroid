package dev.oai.subtitlevideo.whisper

internal object WhisperNative {
    init {
        System.loadLibrary("subtitle_whisper")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): String
}
