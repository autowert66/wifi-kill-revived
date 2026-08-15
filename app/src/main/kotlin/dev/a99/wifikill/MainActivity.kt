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
import dev.a99.wifikill.model.Host
import dev.a99.wifikill.HostListAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: HostListAdapter

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OuiLookup.init(this)
        if (!RootExecutor.requireRoot()) showRootDialog()

        adapter = HostListAdapter { host, checked ->
            viewModel.toggleKill(host, checked)
        }
        binding.hostList.adapter = adapter

        binding.scanFab.setOnClickListener { viewModel.requestScan() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.hosts.collect { hosts ->
                        adapter.submitList(hosts)
                        binding.emptyView.visibility =
                            if (hosts.isEmpty()) View.VISIBLE else View.GONE
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