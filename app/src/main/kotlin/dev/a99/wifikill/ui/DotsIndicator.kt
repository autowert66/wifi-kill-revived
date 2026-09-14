package dev.a99.wifikill.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.color.MaterialColors

/**
 * Material 3 page indicator: inactive dots with an elongated pill for the
 * selected page, animating between selections like the M3 carousel indicator.
 */
class DotsIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotSize = 6 * density
    private val activeSize = 20 * density
    private val gap = 6 * density
    private val radius = dotSize / 2

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    private var inactiveColor = 0
    private var activeColor = 0

    var count = 0
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selection = 0
        private set

    private var fromSelection = 0
    private var animProgress = 1f
    private var animator: ValueAnimator? = null

    fun setSelection(index: Int) {
        if (index == selection) return
        fromSelection = selection
        selection = index
        updateContentDescription()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val extra = activeSize - dotSize
        val width = if (count > 0) (count * dotSize + (count - 1) * gap + extra).toInt() else 0
        val height = (dotSize + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count <= 0) return
        if (inactiveColor == 0) {
            inactiveColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
            activeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        }

        val cy = paddingTop + (measuredHeight - paddingTop - paddingBottom) / 2f
        val extra = activeSize - dotSize
        var x = paddingLeft + extra / 2f
        for (i in 0 until count) {
            val w = when (i) {
                selection -> dotSize + extra * animProgress
                fromSelection -> activeSize - extra * animProgress
                else -> dotSize
            }
            paint.color = if (i == selection) activeColor else inactiveColor
            rect.set(x, cy - radius, x + w, cy + radius)
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += w + gap
        }
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(dev.a99.wifikill.R.string.ob_page_a11y, selection + 1, count)
    }
}
