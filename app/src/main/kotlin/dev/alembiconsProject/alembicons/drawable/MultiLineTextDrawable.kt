package dev.alembiconsProject.alembicons.drawable

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withTranslation

class MultiLineTextDrawable(
    text: CharSequence
    , typeFace: Typeface
    , defaultTextSize: Float
    , minTextSize: Float
    , color: Int
    , private val width: Int
    , maxLines: Int
    , private val height: Int = 0
): BaseTextDrawable() {
    override val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val staticLayout: StaticLayout

    init {
        paint.color = color
        paint.textSize = defaultTextSize
        paint.typeface = typeFace
        adjustTextSize(text.toString(), minTextSize, width, maxLines)

        staticLayout = buildStaticLayout(text, width, maxLines)
    }

    private fun buildStaticLayout(text: CharSequence, width: Int, maxLines: Int): StaticLayout {
        // minSdk is 23, so the Builder API is always available.
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
    }

    private fun adjustTextSize(text: String, minTextSize: Float, width: Int, maxLines: Int) {
        val words = text.split(' ')
        val longestWord = widestWord(words, maxLines) { textWidth(it) }

        while (textWidth(longestWord) > width && paint.textSize > minTextSize) {
            paint.textSize -= 1
        }
    }

    private fun calculateX(): Float {
        if (width > 0) {
            return (width - staticLayout.width) / 2F
        }

        return (bounds.width() - staticLayout.width) / 2F
    }

    private fun calculateY(): Float {
        if (height > 0) {
            return (height - staticLayout.height) / 2F
        }

        return (bounds.height() - staticLayout.height) / 2F
    }

    override fun getIntrinsicWidth(): Int {
        return staticLayout.width
    }

    override fun getIntrinsicHeight(): Int {
        return staticLayout.height
    }

    override fun getPaths(): List<Path> {
        val paths = mutableListOf<Path>()
        val baseX = calculateX()
        val baseY = calculateY()

        for (line in 0 until staticLayout.lineCount) {
            val start = staticLayout.getLineStart(line)
            val end = staticLayout.getLineVisibleEnd(line)
            val x = staticLayout.getLineLeft(line) + baseX
            val y = staticLayout.getLineBaseline(line).toFloat() + baseY

            val path = Path()
            paint.getTextPath(staticLayout.text.toString(), start, end, x, y, path)

            paths.add(path)
        }

        return paths
    }

    override fun draw(canvas: Canvas) {
        canvas.withTranslation(calculateX(), calculateY()) {
            staticLayout.draw(this)
        }
    }
}

/**
 * The widest of the first [maxLines] words by [widthOf] — the word the text must shrink to fit.
 * Pure (no paint state) so it can be unit-tested; the drawable passes its text measurement in.
 */
internal fun widestWord(words: List<String>, maxLines: Int, widthOf: (String) -> Int): String {
    if (words.size == 1) return words[0]
    val wordsToShow = if (words.size > maxLines) words.slice(0 until maxLines) else words
    return wordsToShow.maxByOrNull(widthOf) ?: wordsToShow.first()
}