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
    }

    private val scanner = NetworkScanner(app)
    private val resolver = HostnameResolver()
    private val spoofer = ArpSpoofer(app)

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
        viewModelScope.launch {
            if (kill) {
                val ok = try {
                    spoofer.kill(host)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    _events.emit(Event.KillFailed)
                    return@launch
                }
                updateHost(host.ip) { it.copy(isKilled = true) }
                killedIps.add(host.ip)
            } else {
                spoofer.unkill(host)
                killedIps.remove(host.ip)
                updateHost(host.ip) { it.copy(isKilled = false) }
            }
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

    fun shutdown() {
        spoofer.unkillAll()
    }

    override fun onCleared() {
        super.onCleared()
        spoofer.unkillAll()
    }
}