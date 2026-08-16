package dev.a99.wifikill

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.a99.wifikill.databinding.ActivityMainBinding
import dev.a99.wifikill.ui.HostListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: HostListAdapter
    private var updatingKillAllSwitch = false

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                            if (scanning) View.VISIBLE else View.GONE
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

    private fun showTab(showHome: Boolean) {
        binding.homeContent.visibility = if (showHome) View.VISIBLE else View.GONE
        binding.settingsContent.visibility = if (showHome) View.GONE else View.VISIBLE
        binding.scanFab.visibility = if (showHome) View.VISIBLE else View.GONE
        binding.toolbar.title = getString(if (showHome) R.string.app_name else R.string.nav_settings)
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
