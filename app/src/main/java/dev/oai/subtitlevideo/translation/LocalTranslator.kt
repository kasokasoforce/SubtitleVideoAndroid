package dev.oai.subtitlevideo.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.oai.subtitlevideo.srt.SubtitleEntry
import java.io.Closeable

/**
 * Free on-device subtitle translation using ML Kit.
 * Translation model download is the only network step; translation itself runs locally afterwards.
 */
class LocalTranslator(
    sourceLanguageCode: String,
    targetLanguageCode: String,
) : Closeable {
    private val source = normalize(sourceLanguageCode)
    private val target = normalize(targetLanguageCode)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
    )

    fun prepare() {
        val conditions = DownloadConditions.Builder().build()
        Tasks.await(translator.downloadModelIfNeeded(conditions))
    }

    fun translate(
        entries: List<SubtitleEntry>,
        onProgress: (Int) -> Unit = {},
    ): List<SubtitleEntry> {
        require(entries.isNotEmpty()) { "翻訳する字幕がありません" }
        prepare()
        return entries.mapIndexed { index, entry ->
            val translated = Tasks.await(translator.translate(entry.text)).trim()
            require(translated.isNotEmpty()) { "字幕 ${entry.index} の翻訳結果が空です" }
            onProgress((((index + 1) * 100L) / entries.size).toInt())
            entry.copy(text = translated)
        }
    }

    override fun close() = translator.close()

    companion object {
        fun isSupported(languageCode: String): Boolean = runCatching { normalize(languageCode) }.isSuccess

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
