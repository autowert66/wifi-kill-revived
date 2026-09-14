package dev.a99.wifikill

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import dev.a99.wifikill.databinding.ActivityOnboardingBinding
import dev.a99.wifikill.databinding.PageOnboardingRootBinding
import dev.a99.wifikill.ui.OnboardingPage
import dev.a99.wifikill.ui.OnboardingPageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch tour: five story pages, a fair-use confirmation, and a root
 * check. Completed only by reaching the last page; aborted tours reappear on
 * the next launch. Also replayable from Settings.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class RootStatus { UNCHECKED, CHECKING, GRANTED, MISSING }

    private companion object {
        const val CONFIRM_POSITION = 4
        const val STATE_ACCEPTED = "accepted"

        // KernelSU hides its su binary from ungranted apps, so once a grant
        // was seen in this process there is no point in probing again.
        @Volatile
        var sessionRootGranted = false
    }

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingPageAdapter

    private var accepted = false
    private var rootState = RootStatus.UNCHECKED
    private var rootPage: PageOnboardingRootBinding? = null

    private val rootTopSpacerHeightPx by lazy { (140 * resources.displayMetrics.density).toInt() }

    private val pages = listOf(
        OnboardingPage.Intro(
            R.string.ob_welcome_title, R.string.ob_welcome_body,
            R.drawable.ic_ob_wifi, R.drawable.shape_ob_flower,
            MAttr.colorPrimaryContainer, MAttr.colorOnPrimaryContainer,
        ),
        OnboardingPage.Intro(
            R.string.ob_scan_title, R.string.ob_scan_body,
            R.drawable.ic_ob_radar, R.drawable.shape_ob_cookie9,
            MAttr.colorSecondaryContainer, MAttr.colorOnSecondaryContainer,
        ),
        OnboardingPage.Intro(
            R.string.ob_block_title, R.string.ob_block_body,
            R.drawable.ic_ob_block, R.drawable.shape_ob_cookie6,
            MAttr.colorTertiaryContainer, MAttr.colorOnTertiaryContainer,
        ),
        OnboardingPage.Intro(
            R.string.ob_bandwidth_title, R.string.ob_bandwidth_body,
            R.drawable.ic_ob_speed, R.drawable.shape_ob_sunny,
            MAttr.colorPrimaryContainer, MAttr.colorOnPrimaryContainer,
        ),
        OnboardingPage.Confirm(
            R.string.ob_fair_title, R.string.ob_fair_body,
            R.drawable.ic_ob_warning, R.drawable.shape_ob_soft_burst,
            MAttr.colorErrorContainer, MAttr.colorOnErrorContainer,
        ),
        OnboardingPage.Root(
            R.string.ob_root_title, R.string.ob_root_body,
            R.drawable.ic_ob_key, R.drawable.shape_ob_cookie12,
            MAttr.colorSecondaryContainer, MAttr.colorOnSecondaryContainer,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()

        accepted = savedInstanceState?.getBoolean(STATE_ACCEPTED, false) ?: false
        adapter = OnboardingPageAdapter(pages)
        adapter.accepted = accepted
        adapter.onAcceptanceChanged = { checked ->
            accepted = checked
            syncGating()
        }
        adapter.onRootPageBound = { rootPageBinding ->
            rootPage = rootPageBinding
            rootPageBinding.rootRetry.setOnClickListener { checkRoot() }
            rootPageBinding.rootLearnMore.setOnClickListener { openRootExplainer() }
            rootPageBinding.exploreAnyway.setOnClickListener { complete() }
            applyRootState()
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        with(binding) {
            dots.count = pages.size
            pager.adapter = adapter
            // Keep every page alive: the checkbox state and the root status
            // views must never be recycled mid-tour.
            pager.offscreenPageLimit = pages.size - 1
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (!accepted && position > CONFIRM_POSITION) {
                        pager.setCurrentItem(CONFIRM_POSITION, false)
                        return
                    }
                    dots.setSelection(position)
                    backButton.isInvisible = position == 0
                    syncGating()
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    // On the fair-use page, block a forward swipe until the
                    // checkbox is ticked, but keep the backward swipe free.
                    if (!accepted && position == CONFIRM_POSITION && positionOffset > 0f) {
                        pager.setCurrentItem(CONFIRM_POSITION, false)
                    }
                }
            })

            backButton.setOnClickListener {
                pager.setCurrentItem(pager.currentItem - 1, true)
            }
            nextFab.setOnClickListener {
                pager.setCurrentItem((pager.currentItem + 1).coerceAtMost(pages.lastIndex), true)
            }
            startButton.setOnClickListener { complete() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.pager.currentItem > 0) {
                    binding.pager.setCurrentItem(binding.pager.currentItem - 1, true)
                } else {
                    finish()
                }
            }
        })

        syncGating()
        if (sessionRootGranted) {
            rootState = RootStatus.GRANTED
            applyRootState()
        } else {
            checkRoot()
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a root manager: the grant may have just happened.
        if (rootState == RootStatus.MISSING) checkRoot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ACCEPTED, accepted)
    }

    /**
     * Gates that keep the tour honest: the fair-use checkbox must be ticked
     * before the disclaimer page can be left forwards, and the final continue
     * button stays disabled until root is confirmed.
     */
    private fun syncGating() {
        val position = binding.pager.currentItem
        val last = position == pages.lastIndex
        if (last) {
            binding.nextFab.hide()
            binding.startButton.show()
        } else {
            binding.startButton.hide()
            binding.nextFab.show()
        }
        binding.nextFab.isEnabled = position != CONFIRM_POSITION || accepted
        binding.startButton.isEnabled = rootState == RootStatus.GRANTED
    }

    private fun checkRoot() {
        if (rootState == RootStatus.CHECKING) return
        rootState = RootStatus.CHECKING
        applyRootState()
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) { RootExecutor.requireRoot() }
            if (isFinishing || isDestroyed) return@launch
            if (granted) sessionRootGranted = true
            rootState = if (granted) RootStatus.GRANTED else RootStatus.MISSING
            applyRootState()
        }
    }

    private fun applyRootState() {
        val page = rootPage ?: return
        when (rootState) {
            RootStatus.UNCHECKED, RootStatus.CHECKING -> {
                page.rootStatusCard.setCardBackgroundColor(colorAttr(MAttr.colorSurfaceContainerHighest))
                page.rootStatusProgress.isVisible = true
                page.rootStatusIcon.isVisible = false
                page.rootStatusTitle.setText(R.string.ob_root_checking)
                page.rootStatusTitle.setTextColor(colorAttr(MAttr.colorOnSurface))
                page.rootStatusBody.isVisible = false
                page.rootStatusActions.isVisible = false
                page.exploreAnyway.isVisible = false
            }
            RootStatus.GRANTED -> {
                page.rootStatusCard.setCardBackgroundColor(colorAttr(MAttr.colorSurfaceContainerHighest))
                page.rootStatusProgress.isVisible = false
                page.rootStatusIcon.isVisible = true
                page.rootStatusIcon.setImageResource(R.drawable.ic_ob_check_circle)
                page.rootStatusIcon.imageTintList = colorAttr(MAttr.colorPrimary).toTintList()
                page.rootStatusTitle.setText(R.string.ob_root_granted_title)
                page.rootStatusTitle.setTextColor(colorAttr(MAttr.colorOnSurface))
                page.rootStatusBody.setText(R.string.ob_root_granted_body)
                page.rootStatusBody.setTextColor(colorAttr(MAttr.colorOnSurfaceVariant))
                page.rootStatusBody.isVisible = true
                page.rootStatusActions.isVisible = false
                page.exploreAnyway.isVisible = false
            }
            RootStatus.MISSING -> {
                page.rootStatusCard.setCardBackgroundColor(colorAttr(MAttr.colorErrorContainer))
                page.rootStatusProgress.isVisible = false
                page.rootStatusIcon.isVisible = true
                page.rootStatusIcon.setImageResource(R.drawable.ic_ob_error)
                page.rootStatusIcon.imageTintList = colorAttr(MAttr.colorOnErrorContainer).toTintList()
                page.rootStatusTitle.setText(R.string.ob_root_missing_title)
                page.rootStatusTitle.setTextColor(colorAttr(MAttr.colorOnErrorContainer))
                page.rootStatusBody.setText(R.string.ob_root_missing_body)
                page.rootStatusBody.setTextColor(colorAttr(MAttr.colorOnErrorContainer))
                page.rootStatusBody.isVisible = true
                val onError = colorAttr(MAttr.colorOnErrorContainer).toTintList()
                page.rootRetry.setTextColor(onError)
                page.rootLearnMore.setTextColor(onError)
                page.rootStatusActions.isVisible = true
                page.exploreAnyway.isVisible = true
            }
        }
        fitRootContent()
        syncGating()
    }

    /**
     * The root page shares the uniform hero offset with the other pages, but
     * its "no root" card can outgrow the screen. When that content would
     * otherwise force a scroll, shrink the top spacer (dropping the icon from
     * its uniform position) so everything stays on screen. When it fits, the
     * uniform offset is preserved untouched.
     */
    private fun fitRootContent() {
        val page = rootPage ?: return
        val scroll = page.root
        if (scroll.height <= 0) {
            scroll.post { fitRootContent() }
            return
        }
        val content = page.rootContent
        val spacer = page.topSpacer
        val lp = spacer.layoutParams
        lp.height = 0
        spacer.layoutParams = lp
        content.measure(
            View.MeasureSpec.makeMeasureSpec(scroll.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val spare = scroll.height - content.measuredHeight
        lp.height = spare.coerceIn(0, rootTopSpacerHeightPx)
        spacer.layoutParams = lp
        content.requestLayout()
    }

    private fun colorAttr(attr: Int): Int = MaterialColors.getColor(binding.root, attr)

    private fun Int.toTintList(): ColorStateList = ColorStateList.valueOf(this)

    private fun openRootExplainer() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.ob_root_learn_more_url)))
        runCatching { startActivity(intent) }
    }

    private fun complete() {
        OnboardingPrefs.markCompleted(this)
        finish()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isNight =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.onboardingRoot.updatePadding(top = statusBars.top)
            binding.bottomBar.updatePadding(bottom = navBars.bottom)
            insets
        }
    }
}

/** Material 3 color attribute references, imported under a short alias. */
private typealias MAttr = com.google.android.material.R.attr
