package dev.a99.wifikill

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import dev.a99.wifikill.databinding.ActivityMainBinding
import dev.a99.wifikill.ui.HostListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: HostListAdapter
    private var updatingKillAllSwitch = false
    private var currentTabHome = true

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets()

        lifecycleScope.launch {
            val rooted = withContext(Dispatchers.IO) {
                OuiLookup.init(this@MainActivity)
                RootExecutor.requireRoot()
            }
            if (!rooted) showRootDialog()
        }

        adapter = HostListAdapter { host, checked ->
            viewModel.toggleKill(host, checked)
        }
        binding.hostList.adapter = adapter

        binding.scanFab.setOnClickListener { viewModel.requestScan() }

        binding.killAllSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingKillAllSwitch) viewModel.toggleAll(checked)
        }

        binding.bottomNav.selectedItemId = R.id.nav_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showTab(true)
                R.id.nav_settings -> showTab(false)
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        binding.aboutVersionText.text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.hosts.collect { hosts ->
                        adapter.submitList(hosts)
                        binding.emptyView.visibility =
                            if (hosts.isEmpty()) View.VISIBLE else View.GONE
                        val allKilled = hosts.isNotEmpty() && hosts.all { it.isKilled }
                        updatingKillAllSwitch = true
                        binding.killAllSwitch.isEnabled = hosts.isNotEmpty()
                        binding.killAllSwitch.isChecked = allKilled
                        updatingKillAllSwitch = false
                    }
                }
                launch {
                    viewModel.scanning.collect { scanning ->
                        binding.scanProgress.visibility =
                            if (scanning) View.VISIBLE else View.INVISIBLE
                    }
                }
                launch {
                    viewModel.events.collect { ev ->
                        when (ev) {
                            is MainViewModel.Event.ScanFailed ->
                                Toast.makeText(this@MainActivity, R.string.scan_failed, Toast.LENGTH_LONG).show()
                            is MainViewModel.Event.KillFailed ->
                                Toast.makeText(this@MainActivity, R.string.kill_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.toolbar.setPadding(0, statusBars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, navBars.bottom)

            val density = resources.displayMetrics.density
            val bottomNavHeight = (80 * density).toInt() + navBars.bottom
            val fabMargin = bottomNavHeight + (16 * density).toInt()
            (binding.scanFab.layoutParams as FrameLayout.LayoutParams).bottomMargin = fabMargin

            insets
        }
    }

    private fun showTab(showHome: Boolean) {
        if (showHome == currentTabHome) return
        currentTabHome = showHome

        TransitionManager.beginDelayedTransition(
            binding.contentContainer,
            MaterialSharedAxis(MaterialSharedAxis.X, !showHome),
        )

        binding.homeContent.visibility = if (showHome) View.VISIBLE else View.GONE
        binding.settingsContent.visibility = if (showHome) View.GONE else View.VISIBLE
        binding.toolbar.title = getString(if (showHome) R.string.app_name else R.string.nav_settings)

        animateFab(showHome)
    }

    private fun animateFab(visible: Boolean) {
        binding.scanFab.animate().cancel()
        if (visible) {
            binding.scanFab.visibility = View.VISIBLE
            binding.scanFab.scaleX = 0f
            binding.scanFab.scaleY = 0f
            binding.scanFab.alpha = 0f
            binding.scanFab.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            binding.scanFab.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(160)
                .withEndAction { binding.scanFab.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun showRootDialog() {
        AlertDialog.Builder(this)
            .setMessage(R.string.root_required)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.shutdown()
    }
}
