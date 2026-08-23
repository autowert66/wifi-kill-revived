package dev.a99.wifikill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.a99.wifikill.model.Host
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Event {
        object ScanFailed : Event
        object KillFailed : Event

        /** A spoofer died unexpectedly; its victim has been restored. */
        data class SpooferDied(val ip: String) : Event
    }

    private val scanner = NetworkScanner(app)
    private val resolver = HostnameResolver()
    private val appContext = app
    private val spoofer get() = WifiKillApp.get(appContext).spoofer

    init {
        spoofer.startWatchdog(viewModelScope)
        viewModelScope.launch {
            spoofer.deaths.collect { ip ->
                synchronized(hostsMutex) { killedIps.remove(ip) }
                updateHost(ip) { it.copy(isKilled = false) }
                _events.emit(Event.SpooferDied(ip))
            }
        }
        // Re-attach to blocking sessions a crashed previous instance left
        // behind (only possible when its pid was recycled), and surface them.
        viewModelScope.launch {
            val adopted = try {
                spoofer.reconcile()
            } catch (e: Exception) {
                android.util.Log.e("WifiKill", "reconcile failed", e)
                emptyList()
            }
            android.util.Log.d("WifiKill", "reconcile adopted=${adopted.size}: ${adopted.map { it.ip }}")
            if (adopted.isEmpty()) return@launch
            synchronized(hostsMutex) {
                val snapshot = _hosts.value.toMutableList()
                for (host in adopted) {
                    killedIps.add(host.ip)
                    val idx = snapshot.indexOfFirst { it.ip == host.ip }
                    if (idx >= 0) snapshot[idx] = snapshot[idx].copy(isKilled = true)
                    else snapshot.add(host)
                }
                _hosts.value = snapshot
            }
            KillService.start(appContext)
        }
    }

    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    private var scanJob: kotlinx.coroutines.Job? = null

    private val hostsMutex = Object()
    private val killedIps = mutableSetOf<String>()

    fun requestScan() {
        if (_scanning.value) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _scanning.value = true
            synchronized(hostsMutex) {
                _hosts.value = _hosts.value.filter { it.isKilled }
                killedIps.retainAll(_hosts.value.map { it.ip })
            }
            try {
                scanner.scan().collect { scanned ->
                    val withOui = scanned.copy(manufacturer = OuiLookup.lookup(scanned.mac))
                    synchronized(hostsMutex) {
                        val snapshot = _hosts.value.toMutableList()
                        val existingIdx = snapshot.indexOfFirst { it.ip == scanned.ip }
                        if (existingIdx >= 0) {
                            if (!snapshot[existingIdx].isKilled) {
                                snapshot[existingIdx] = snapshot[existingIdx].copy(
                                    manufacturer = withOui.manufacturer
                                )
                            } else {
                                snapshot[existingIdx] = snapshot[existingIdx].copy(
                                    mac = withOui.mac,
                                    manufacturer = withOui.manufacturer,
                                    hostname = withOui.hostname,
                                )
                            }
                        } else {
                            snapshot.add(withOui)
                        }
                        _hosts.value = snapshot
                        val h = _hosts.value.first { it.ip == scanned.ip }
                        if (h.hostname == null) resolveHostname(h)
                    }
                }
            } catch (e: Exception) {
                _events.emit(Event.ScanFailed)
            } finally {
                _scanning.value = false
            }
        }
        scanJob?.start()
    }

    private fun resolveHostname(host: Host) {
        viewModelScope.launch {
            val name = resolver.resolve(host.ip)
            if (name != null) {
                synchronized(hostsMutex) {
                    val idx = _hosts.value.indexOfFirst { it.ip == host.ip }
                    if (idx >= 0 && _hosts.value[idx].hostname == null) {
                        val updated = _hosts.value.toMutableList()
                        updated[idx] = updated[idx].copy(hostname = name)
                        _hosts.value = updated
                    }
                }
            }
        }
    }

    fun toggleKill(host: Host, kill: Boolean) {
        viewModelScope.launch { setKill(host, kill) }
    }

    fun toggleAll(kill: Boolean) {
        viewModelScope.launch {
            val targets = synchronized(hostsMutex) { _hosts.value.toList() }
            for (host in targets) {
                if (host.isKilled != kill) setKill(host, kill)
            }
        }
    }

    private suspend fun setKill(host: Host, kill: Boolean) {
        if (kill) {
            val ok = try {
                spoofer.kill(host)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                _events.emit(Event.KillFailed)
                return
            }
            updateHost(host.ip) { it.copy(isKilled = true) }
            killedIps.add(host.ip)
            KillService.start(appContext)
        } else {
            spoofer.unkill(host)
            killedIps.remove(host.ip)
            updateHost(host.ip) { it.copy(isKilled = false) }
        }
    }

    private fun updateHost(ip: String, transform: (Host) -> Host) {
        synchronized(hostsMutex) {
            val idx = _hosts.value.indexOfFirst { it.ip == ip }
            if (idx >= 0) {
                val updated = _hosts.value.toMutableList()
                updated[idx] = transform(updated[idx])
                _hosts.value = updated
            }
        }
    }

    /**
     * Called once root access is confirmed. Startup recovery relies solely
     * on [ArpSpoofer.reconcile] (run unconditionally at init): surviving
     * spoofers are adopted into the UI rather than killed, so an app crash
     * no longer destroys an ongoing blocking session.
     */
    fun onRootAvailable() {
        // Reserved for future startup work; reconciliation already ran.
    }

    fun shutdown() {
        spoofer.unkillAll()
    }

    override fun onCleared() {
        super.onCleared()
        spoofer.unkillAll()
    }
}