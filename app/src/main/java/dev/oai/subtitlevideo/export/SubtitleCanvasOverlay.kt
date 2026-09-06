package dev.oai.subtitlevideo.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import dev.oai.subtitlevideo.settings.AppSettings
import dev.oai.subtitlevideo.srt.DisplaySubtitle
import dev.oai.subtitlevideo.srt.SubtitleChunker
import kotlin.math.max

@UnstableApi
class SubtitleCanvasOverlay(
    private val timeline: List<DisplaySubtitle>,
    private val settings: AppSettings,
) : CanvasOverlay(true) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val item = findActive(presentationTimeUs / 1000L) ?: return
        val textSize = max(14f, canvas.height * 0.040f * settings.subtitleTextScale)
        paint.textSize = textSize
        val shadowStrength = settings.shadowPercent / 100f
        if (shadowStrength > 0f) {
            paint.setShadowLayer(
                max(1.0f, textSize * (0.02f + 0.07f * shadowStrength)),
                0f,
                textSize * 0.045f,
                ((80 + 175 * shadowStrength).toInt().coerceIn(0, 255) shl 24),
            )
        } else {
            paint.clearShadowLayer()
        }

        val lines = SubtitleChunker.wrapLines(item.text, settings.maxLineChars).take(settings.maxLines)
        if (lines.isEmpty()) return
        val lineHeight = textSize * 1.18f
        val marginBottom = max(24f, canvas.height * (settings.subtitleBottomMarginPercent / 100f))
        val lastBaseline = canvas.height - marginBottom
        val firstBaseline = lastBaseline - lineHeight * (lines.size - 1)
        val centerX = canvas.width / 2f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, centerX, firstBaseline + index * lineHeight, paint)
        }
    }

    private fun findActive(timeMs: Long): DisplaySubtitle? {
        var low = 0
        var high = timeline.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val item = timeline[mid]
            when {
                timeMs < item.startMs -> high = mid - 1
                timeMs >= item.endMs -> low = mid + 1
                else -> return item
            }
        }
        return null
    }
}
