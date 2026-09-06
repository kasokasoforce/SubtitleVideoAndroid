package dev.oai.subtitlevideo.settings

import android.content.Context
import dev.oai.subtitlevideo.model.WhisperModelSpec

data class AppSettings(
    val recognitionLanguageCode: String = "zh",
    val recognitionLanguageLabel: String = "中国語",
    val targetLanguageCode: String = "ja",
    val targetLanguageLabel: String = "日本語",
    val translationMode: String = "chatgpt",
    val whisperModel: WhisperModelSpec = WhisperModelSpec.SMALL,
    val vadEnabled: Boolean = false,
    val wordTimingEnabled: Boolean = false,
    val subtitleTextScale: Float = 0.7f,
    val subtitleBottomMarginPercent: Int = 6,
    val maxLineChars: Int = 24,
    val maxLines: Int = 2,
    val shadowPercent: Int = 65,
    val maxEventSeconds: Double = 4.0,
) {
    companion object {
        private const val PREFS = "app_settings"
        private const val SPEED_DEFAULTS_VERSION = 2

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedDefaultsVersion = p.getInt("speedDefaultsVersion", 0)
            val useSpeedDefaults = savedDefaultsVersion < SPEED_DEFAULTS_VERSION
            if (useSpeedDefaults) {
                p.edit()
                    .putBoolean("wordTimingEnabled", false)
                    .putInt("speedDefaultsVersion", SPEED_DEFAULTS_VERSION)
                    .apply()
            }
            return AppSettings(
                recognitionLanguageCode = p.getString("recognitionLanguageCode", "zh") ?: "zh",
                recognitionLanguageLabel = p.getString("recognitionLanguageLabel", "中国語") ?: "中国語",
                targetLanguageCode = p.getString("targetLanguageCode", "ja") ?: "ja",
                targetLanguageLabel = p.getString("targetLanguageLabel", "日本語") ?: "日本語",
                translationMode = p.getString("translationMode", "chatgpt") ?: "chatgpt",
                whisperModel = WhisperModelSpec.fromId(p.getString("whisperModel", null)),
                vadEnabled = p.getBoolean("vadEnabled", false),
                wordTimingEnabled = if (useSpeedDefaults) false else p.getBoolean("wordTimingEnabled", false),
                subtitleTextScale = p.getFloat("subtitleTextScale", 0.7f).coerceIn(0.4f, 1.6f),
                subtitleBottomMarginPercent = p.getInt("subtitleBottomMarginPercent", 6).coerceIn(2, 20),
                maxLineChars = p.getInt("maxLineChars", 24).coerceIn(12, 40),
                maxLines = p.getInt("maxLines", 2).coerceIn(1, 3),
                shadowPercent = p.getInt("shadowPercent", 65).coerceIn(0, 100),
                maxEventSeconds = p.getFloat("maxEventSeconds", 4.0f).toDouble().coerceIn(1.5, 8.0),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("recognitionLanguageCode", recognitionLanguageCode)
            .putString("recognitionLanguageLabel", recognitionLanguageLabel)
            .putString("targetLanguageCode", targetLanguageCode)
            .putString("targetLanguageLabel", targetLanguageLabel)
            .putString("translationMode", translationMode)
            .putString("whisperModel", whisperModel.id)
            .putBoolean("vadEnabled", vadEnabled)
            .putBoolean("wordTimingEnabled", wordTimingEnabled)
            .putInt("speedDefaultsVersion", SPEED_DEFAULTS_VERSION)
            .putFloat("subtitleTextScale", subtitleTextScale)
            .putInt("subtitleBottomMarginPercent", subtitleBottomMarginPercent)
            .putInt("maxLineChars", maxLineChars)
            .putInt("maxLines", maxLines)
            .putInt("shadowPercent", shadowPercent)
            .putFloat("maxEventSeconds", maxEventSeconds.toFloat())
            .apply()
    }
}
