package dev.oai.subtitlevideo.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import dev.oai.subtitlevideo.srt.DisplaySubtitle
import dev.oai.subtitlevideo.srt.SubtitleChunker
import kotlin.math.max

@UnstableApi
class SubtitleCanvasOverlay(
    private val timeline: List<DisplaySubtitle>,
) : CanvasOverlay(true) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val item = findActive(presentationTimeUs / 1000L) ?: return
        val textSize = max(28f, canvas.height * 0.040f)
        paint.textSize = textSize
        paint.setShadowLayer(max(1.5f, textSize * 0.065f), 0f, textSize * 0.045f, 0x99000000.toInt())

        val lines = SubtitleChunker.wrapLines(item.text, 24).take(2)
        if (lines.isEmpty()) return
        val lineHeight = textSize * 1.18f
        val marginBottom = max(48f, canvas.height * 0.055f)
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
