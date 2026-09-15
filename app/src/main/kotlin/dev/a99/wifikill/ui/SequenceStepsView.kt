package dev.a99.wifikill.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView
import dev.a99.wifikill.R

/**
 * Renders an ordered list of steps as a vertical timeline: a numbered badge
 * per step joined by a connector, with the step title and body beside it.
 */
class SequenceStepsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Step(val title: String, val body: String)

    private val density = resources.displayMetrics.density

    private fun dp(value: Int) = (value * density).toInt()

    init {
        orientation = VERTICAL
    }

    fun setSteps(steps: List<Step>) {
        removeAllViews()
        steps.forEachIndexed { index, step ->
            addView(buildStep(index + 1, step, isLast = index == steps.lastIndex))
        }
    }

    private fun buildStep(number: Int, step: Step, isLast: Boolean): View {
        val indicator = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.MATCH_PARENT)
        }

        indicator.addView(
            MaterialTextView(context).apply {
                text = number.toString()
                gravity = Gravity.CENTER
                setTextAppearance(R.style.TextAppearance_WifiKill_StepBadge)
                setBackgroundResource(R.drawable.shape_step_badge)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            },
        )

        if (!isLast) {
            indicator.addView(
                View(context).apply {
                    setBackgroundResource(R.drawable.shape_step_connector)
                    layoutParams = LinearLayout.LayoutParams(dp(2), 0, 1f)
                },
            )
        }

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            }
            setPadding(0, 0, 0, if (isLast) 0 else dp(16))
        }

        textColumn.addView(
            MaterialTextView(context).apply {
                text = step.title
                setTextAppearance(R.style.TextAppearance_WifiKill_StepTitle)
            },
        )

        textColumn.addView(
            MaterialTextView(context).apply {
                text = step.body
                setTextAppearance(R.style.TextAppearance_WifiKill_StepBody)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
            },
        )

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(indicator)
            addView(textColumn)
        }
    }
}
