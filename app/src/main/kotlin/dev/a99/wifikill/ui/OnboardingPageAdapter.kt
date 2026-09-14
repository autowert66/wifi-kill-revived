package dev.a99.wifikill.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.color.MaterialColors
import dev.a99.wifikill.databinding.PageOnboardingConfirmBinding
import dev.a99.wifikill.databinding.PageOnboardingIntroBinding
import dev.a99.wifikill.databinding.PageOnboardingRootBinding

sealed class OnboardingPage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val shape: Int,
    val containerAttr: Int,
    val onContainerAttr: Int,
    val viewType: Int,
) {
    class Intro(title: Int, body: Int, icon: Int, shape: Int, containerAttr: Int, onContainerAttr: Int) :
        OnboardingPage(title, body, icon, shape, containerAttr, onContainerAttr, VIEW_TYPE_INTRO)

    class Confirm(title: Int, body: Int, icon: Int, shape: Int, containerAttr: Int, onContainerAttr: Int) :
        OnboardingPage(title, body, icon, shape, containerAttr, onContainerAttr, VIEW_TYPE_CONFIRM)

    class Root(title: Int, body: Int, icon: Int, shape: Int, containerAttr: Int, onContainerAttr: Int) :
        OnboardingPage(title, body, icon, shape, containerAttr, onContainerAttr, VIEW_TYPE_ROOT)

    companion object {
        const val VIEW_TYPE_INTRO = 0
        const val VIEW_TYPE_CONFIRM = 1
        const val VIEW_TYPE_ROOT = 2
    }
}

class OnboardingPageAdapter(
    private val pages: List<OnboardingPage>,
) : RecyclerView.Adapter<OnboardingPageAdapter.Holder>() {

    var accepted = false
        set(value) {
            field = value
            confirmBinding?.confirmCheck?.isChecked = value
        }

    var onAcceptanceChanged: ((Boolean) -> Unit)? = null
    var onRootPageBound: ((PageOnboardingRootBinding) -> Unit)? = null

    private var confirmBinding: PageOnboardingConfirmBinding? = null
    private var rootBinding: PageOnboardingRootBinding? = null

    sealed class Holder(root: android.view.View) :
        RecyclerView.ViewHolder(root) {
        class Intro(val binding: PageOnboardingIntroBinding) : Holder(binding.root)
        class Confirm(val binding: PageOnboardingConfirmBinding) : Holder(binding.root)
        class Root(val binding: PageOnboardingRootBinding) : Holder(binding.root)
    }

    override fun getItemCount() = pages.size

    override fun getItemViewType(position: Int) = pages[position].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            OnboardingPage.VIEW_TYPE_CONFIRM -> Holder.Confirm(
                PageOnboardingConfirmBinding.inflate(inflater, parent, false)
            )
            OnboardingPage.VIEW_TYPE_ROOT -> Holder.Root(
                PageOnboardingRootBinding.inflate(inflater, parent, false)
            )
            else -> Holder.Intro(
                PageOnboardingIntroBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val page = pages[position]) {
            is OnboardingPage.Intro -> with((holder as Holder.Intro).binding) {
                bindHero(heroShape, heroIcon, page)
                titleText.setText(page.title)
                bodyText.setText(page.body)
            }
            is OnboardingPage.Confirm -> {
                val binding = (holder as Holder.Confirm).binding
                confirmBinding = binding
                bindHero(binding.heroShape, binding.heroIcon, page)
                binding.titleText.setText(page.title)
                binding.bodyText.setText(page.body)
                binding.confirmCard.setOnClickListener { binding.confirmCheck.toggle() }
                binding.confirmCheck.setOnCheckedChangeListener { _, checked ->
                    accepted = checked
                    onAcceptanceChanged?.invoke(checked)
                }
                binding.confirmCheck.isChecked = accepted
            }
            is OnboardingPage.Root -> {
                val binding = (holder as Holder.Root).binding
                rootBinding = binding
                bindHero(binding.heroShape, binding.heroIcon, page)
                binding.titleText.setText(page.title)
                binding.bodyText.setText(page.body)
                onRootPageBound?.invoke(binding)
            }
        }
    }

    private fun bindHero(shape: ImageView, icon: ImageView, page: OnboardingPage) {
        val view = shape.rootView
        shape.setImageResource(page.shape)
        shape.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(view, page.containerAttr)
        )
        icon.setImageResource(page.icon)
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(view, page.onContainerAttr)
        )
    }

    fun rootPage(): PageOnboardingRootBinding? = rootBinding

    fun confirmPage(): PageOnboardingConfirmBinding? = confirmBinding
}
