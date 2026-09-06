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
    private data class BatchItem(val sourceIndex: Int, val entry: SubtitleEntry)

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

        return translateInBatches(client, entries, onProgress)
    }

    private fun translateInBatches(
        client: Translator,
        entries: List<SubtitleEntry>,
        onProgress: (Int) -> Unit,
    ): List<SubtitleEntry> {
        val translated = entries.toMutableList()
        val batches = buildBatches(entries)
        var completed = 0
        var allowBatching = true

        batches.forEach { batch ->
            val result = if (allowBatching && batch.size > 1) {
                runCatching { translateBatch(client, batch) }
                    .getOrElse {
                        allowBatching = false
                        translateOneByOne(client, batch)
                    }
            } else {
                translateOneByOne(client, batch)
            }

            result.forEach { item ->
                translated[item.sourceIndex] = item.entry
            }
            completed += batch.size
            onProgress(((completed * 100L) / entries.size).toInt())
        }

        return translated
    }

    private fun buildBatches(entries: List<SubtitleEntry>): List<List<BatchItem>> {
        val batches = mutableListOf<List<BatchItem>>()
        var current = mutableListOf<BatchItem>()
        var currentChars = 0

        entries.forEachIndexed { index, entry ->
            val textChars = entry.text.length + marker(index).length + 2
            val wouldOverflow = current.isNotEmpty() &&
                (current.size >= BATCH_MAX_ENTRIES || currentChars + textChars > BATCH_MAX_CHARS)
            if (wouldOverflow) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += BatchItem(index, entry)
            currentChars += textChars
        }

        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun translateBatch(client: Translator, batch: List<BatchItem>): List<BatchItem> {
        val request = buildString {
            batch.forEach { item ->
                append(marker(item.sourceIndex)).append('\n')
                append(item.entry.text.trim()).append('\n')
            }
        }
        val raw = Tasks.await(client.translate(request))
        val parsed = parseBatch(raw, batch)
        return batch.map { item ->
            val text = parsed[item.sourceIndex]?.trim().orEmpty()
            require(text.isNotEmpty()) { "字幕 ${item.entry.index} の翻訳結果が空です" }
            item.copy(entry = item.entry.copy(text = text))
        }
    }

    private fun parseBatch(raw: String, batch: List<BatchItem>): Map<Int, String> {
        val matches = markerRegex.findAll(raw).toList()
        require(matches.size == batch.size) { "翻訳結果の区切りを保持できませんでした" }

        val expectedIds = batch.map { it.sourceIndex }.toSet()
        val parsed = mutableMapOf<Int, String>()
        matches.forEachIndexed { index, match ->
            val sourceIndex = match.groupValues[1].toInt()
            require(sourceIndex in expectedIds) { "翻訳結果の字幕番号が一致しません" }
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: raw.length
            parsed[sourceIndex] = raw.substring(start, end).trim()
        }
        require(parsed.keys == expectedIds) { "翻訳結果の字幕数が一致しません" }
        return parsed
    }

    private fun translateOneByOne(client: Translator, batch: List<BatchItem>): List<BatchItem> =
        batch.map { item ->
            val translated = Tasks.await(client.translate(item.entry.text)).trim()
            require(translated.isNotEmpty()) { "字幕 ${item.entry.index} の翻訳結果が空です" }
            item.copy(entry = item.entry.copy(text = translated))
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
        private const val BATCH_MAX_ENTRIES = 12
        private const val BATCH_MAX_CHARS = 1_800
        private val markerRegex = Regex("""@@SV_(\d+)@@""")

        private fun marker(sourceIndex: Int): String = "@@SV_${sourceIndex}@@"

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
