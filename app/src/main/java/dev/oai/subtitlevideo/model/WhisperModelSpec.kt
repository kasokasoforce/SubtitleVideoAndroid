package dev.oai.subtitlevideo.model

enum class WhisperModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
) {
    SMALL(
        id = "small",
        displayName = "標準: small（約488MB）",
        fileName = "ggml-small.bin",
        expectedSizeBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-small.bin?download=true",
    ),
    MEDIUM_Q5(
        id = "medium-q5",
        displayName = "精度優先: medium q5（約539MB）",
        fileName = "ggml-medium-q5_0.bin",
        expectedSizeBytes = 539_212_467L,
        sha256 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-medium-q5_0.bin?download=true",
    ),
    LARGE_V3_TURBO_Q5(
        id = "large-v3-turbo-q5",
        displayName = "高精度: large-v3-turbo q5（約574MB）",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        expectedSizeBytes = 574_041_195L,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/98aa99a0a9db05ae2342309f5096248665f7cba3/ggml-large-v3-turbo-q5_0.bin?download=true",
    );

    val sizeMb: Long get() = expectedSizeBytes / 1_000_000L

    companion object {
        fun fromId(id: String?): WhisperModelSpec = entries.firstOrNull { it.id == id } ?: SMALL
    }
}
