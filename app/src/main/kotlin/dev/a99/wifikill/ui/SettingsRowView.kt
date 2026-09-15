package dev.a99.wifikill.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.transition.TransitionManager
import com.google.android.material.textview.MaterialTextView
import dev.a99.wifikill.R

/**
 * A single Material settings row: optional leading icon, title, supporting
 * summary, optional trailing value, and an optional chevron. When [rowBody] is
 * supplied the row becomes expandable and reveals the body text on tap.
 */
class SettingsRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val header: LinearLayout
    private val iconView: ImageView
    private val chevronView: ImageView
    private val titleView: MaterialTextView
    private val summaryView: MaterialTextView
    private val valueView: MaterialTextView
    private val bodyView: MaterialTextView
    private val bodyContainer: LinearLayout

    private var expandable = false
    private var hasTextBody = false
    private var hasCustomBody = false

    var isExpanded = false
        private set

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this, true)

        header = findViewById(R.id.settingsRowHeader)
        iconView = findViewById(R.id.settingsRowIcon)
        chevronView = findViewById(R.id.settingsRowChevron)
        titleView = findViewById(R.id.settingsRowTitle)
        summaryView = findViewById(R.id.settingsRowSummary)
        valueView = findViewById(R.id.settingsRowValue)
        bodyView = findViewById(R.id.settingsRowBody)
        bodyContainer = findViewById(R.id.settingsRowBodyContainer)

        val a = context.obtainStyledAttributes(attrs, R.styleable.SettingsRowView)
        try {
            val iconRes = a.getResourceId(R.styleable.SettingsRowView_rowIcon, 0)
            if (iconRes != 0) {
                iconView.setImageResource(iconRes)
                iconView.visibility = View.VISIBLE
            }

            a.getString(R.styleable.SettingsRowView_rowTitle)?.let { titleView.text = it }

            a.getString(R.styleable.SettingsRowView_rowSummary)?.takeIf { it.isNotEmpty() }?.let {
                summaryView.text = it
                summaryView.visibility = View.VISIBLE
            }

            a.getString(R.styleable.SettingsRowView_rowValue)?.let { setValueText(it) }

            val body = a.getString(R.styleable.SettingsRowView_rowBody)
            if (!body.isNullOrEmpty()) {
                hasTextBody = true
                bodyView.text = body
                markExpandable()
            } else if (a.getBoolean(R.styleable.SettingsRowView_rowChevron, false)) {
                chevronView.setImageResource(R.drawable.ic_ob_arrow_forward)
                chevronView.visibility = View.VISIBLE
            }
        } finally {
            a.recycle()
        }
    }

    private fun markExpandable() {
        expandable = true
        chevronView.visibility = View.VISIBLE
        header.setOnClickListener { toggle() }
    }

    /** Replaces the expandable body with an arbitrary view. */
    fun setBodyView(view: View) {
        bodyContainer.removeAllViews()
        bodyContainer.addView(view)
        hasCustomBody = true
        markExpandable()
    }

    fun setValueText(text: CharSequence) {
        valueView.text = text
        valueView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setOnRowClickListener(listener: View.OnClickListener?) {
        header.isClickable = listener != null
        header.isFocusable = listener != null
        header.setOnClickListener(listener)
    }

    fun toggle() = setExpanded(!isExpanded)

    fun setExpanded(expanded: Boolean) {
        if (!expandable || expanded == isExpanded) return
        isExpanded = expanded
        TransitionManager.beginDelayedTransition(this)
        bodyView.visibility = if (expanded && hasTextBody) View.VISIBLE else View.GONE
        bodyContainer.visibility = if (expanded && hasCustomBody) View.VISIBLE else View.GONE
        chevronView.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(200)
            .start()
    }
}
