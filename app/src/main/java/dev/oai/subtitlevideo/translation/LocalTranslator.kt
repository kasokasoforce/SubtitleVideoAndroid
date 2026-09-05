package dev.oai.subtitlevideo.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.Closeable

/** Free on-device subtitle translation using ML Kit. */
class LocalTranslator(
    private val sourceLanguageCode: String,
    private val targetLanguageCode: String,
) : Closeable {
    private var translator: Translator? = null

    fun translate(
        entries: List<SubtitleEntry>,
        onProgress: (Int) -> Unit = {},
    ): List<SubtitleEntry> {
        require(entries.isNotEmpty()) { "翻訳する字幕がありません" }
        val source = if (sourceLanguageCode == "auto") detectSource(entries) else normalize(sourceLanguageCode)
        val target = normalize(targetLanguageCode)
        require(source != target) { "元言語と翻訳先が同じです" }

        val client = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        translator = client
        Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()))

        return entries.mapIndexed { index, entry ->
            val translated = Tasks.await(client.translate(entry.text)).trim()
            require(translated.isNotEmpty()) { "字幕 ${entry.index} の翻訳結果が空です" }
            onProgress((((index + 1) * 100L) / entries.size).toInt())
            entry.copy(text = translated)
        }
    }

    private fun detectSource(entries: List<SubtitleEntry>): String {
        val sample = entries.take(12).joinToString(" ") { it.text }.take(2000)
        val identifier = LanguageIdentification.getClient()
        try {
            val detected = Tasks.await(identifier.identifyLanguage(sample))
            require(detected != "und") { "音声言語を自動判定できませんでした" }
            return normalize(detected)
        } finally {
            identifier.close()
        }
    }

    override fun close() {
        translator?.close()
        translator = null
    }

    companion object {
        fun isSupported(languageCode: String): Boolean = languageCode == "auto" || runCatching { normalize(languageCode) }.isSuccess

        private fun normalize(code: String): String {
            val normalized = when (code.lowercase()) {
                "zh", "zh-cn", "zh-hans" -> TranslateLanguage.CHINESE
                "ja" -> TranslateLanguage.JAPANESE
                "en" -> TranslateLanguage.ENGLISH
                "ko" -> TranslateLanguage.KOREAN
                "es" -> TranslateLanguage.SPANISH
                "fr" -> TranslateLanguage.FRENCH
                "de" -> TranslateLanguage.GERMAN
                "it" -> TranslateLanguage.ITALIAN
                "pt" -> TranslateLanguage.PORTUGUESE
                "ru" -> TranslateLanguage.RUSSIAN
                "ar" -> TranslateLanguage.ARABIC
                "hi" -> TranslateLanguage.HINDI
                "vi" -> TranslateLanguage.VIETNAMESE
                else -> TranslateLanguage.fromLanguageTag(code)
            }
            require(!normalized.isNullOrBlank()) { "ML Kit未対応言語です: $code" }
            return normalized
        }
    }
}
