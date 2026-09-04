package dev.oai.subtitlevideo.translation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object TranslationShare {
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    fun buildRequestFile(context: Context, sourceSrtText: String, baseName: String): File {
        val out = File(context.cacheDir, "${baseName}.translation_request.txt")
        out.writeText(buildPrompt(sourceSrtText, baseName), Charsets.UTF_8)
        return out
    }

    fun shareToChatGpt(context: Context, requestFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", requestFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "添付ファイルの指示に従い、日本語SRTだけを出力してください。"
            )
            clipData = ClipData.newUri(context.contentResolver, requestFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val direct = Intent(intent).setPackage(CHATGPT_PACKAGE)
        if (direct.resolveActivity(context.packageManager) != null) {
            context.startActivity(direct)
        } else {
            context.startActivity(Intent.createChooser(intent, "ChatGPTへ共有"))
        }
    }

    private fun buildPrompt(sourceSrtText: String, baseName: String): String = """
このファイルは、動画字幕を日本語化するための翻訳依頼です。
このチャットでは、この動画1本の字幕翻訳だけを扱ってください。
過去のチャット内容や記憶には頼らず、この指示と下の中国語SRTだけを根拠にしてください。

中国語SRTを自然な日本語SRTへ翻訳してください。
直訳調を避け、前後の流れを見て会話として自然な日本語にしてください。
音声認識ミスらしい箇所は、前後の文脈から自然に補正してください。
元の意味、口調、温度感を不自然に弱めたり強めたりしないでください。
固有名詞と数字は勝手に変更しないでください。
意味が曖昧な部分を創作で補わないでください。
長い字幕はアプリ側で分割するので、翻訳段階で意味を削りすぎないでください。

重要:
- SRT番号は変更しないでください。
- タイムコードは変更しないでください。
- 字幕ブロック数を増減しないでください。
- 字幕本文だけを日本語にしてください。
- Markdownやコードブロックは使わないでください。
- 説明、前置き、補足、感想は書かないでください。
- 返答はSRT本文だけにしてください。
- 可能なら ${baseName}.jp.srt というファイルとして出力してください。

--- 翻訳対象SRT ---
$sourceSrtText
""".trim()
}
