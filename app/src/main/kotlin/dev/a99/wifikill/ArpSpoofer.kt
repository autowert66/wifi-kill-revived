package dev.a99.wifikill

import android.content.Context
import android.os.Process
import dev.a99.wifikill.model.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ArpSpoofer(private val context: Context) {

    /** Everything needed to SIGTERM the spoofer or fire a rescue burst. */
    private class Victim(
        val ip: String,
        val mac: String,
        val pid: Int,
        val iface: String,
        val gatewayIp: String,
        val gatewayMac: String,
    )

    private val victims = ConcurrentHashMap<String, Victim>()

    private val _deaths = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** IPs whose spoofer process vanished without being asked to stop. */
    val deaths: SharedFlow<String> = _deaths

    private val _activeCount = MutableStateFlow(0)

    /** Number of currently tracked blocking sessions; drives the service UI. */
    val activeCount: StateFlow<Int> = _activeCount

    private var watchdogJob: Job? = null

    private fun syncActive() {
        _activeCount.value = victims.size
    }

    private fun getNetworkInfo(): NetworkScanner.NetworkInfo =
        NetworkScanner(context).getNetworkInfo()

    suspend fun getGatewayMac(gatewayIp: String): String? {
        // /proc/net/arp is not readable by apps on modern Android (SELinux),
        // so read it through root.
        val result = RootExecutor.exec("cat /proc/net/arp")
        if (result.exitCode != 0) return null
        return result.stdout
            .drop(1)
            .firstOrNull { it.startsWith(gatewayIp + " ") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(3)
            ?.lowercase()
            ?.takeIf { it != "00:00:00:00:00:00" }
    }

    suspend fun kill(host: Host): Boolean = withContext(Dispatchers.IO) {
        if (victims.containsKey(host.ip)) return@withContext true
        val info = getNetworkInfo()
        // Poisoning the gateway itself (often also an AP) blackholes every
        // client behind it -- never allow it.
        if (host.ip == info.gatewayIp) return@withContext false
        val gatewayMac = getGatewayMac(info.gatewayIp)
            ?: return@withContext false
        val binary = BinaryDeployer.deploy(context, "arpspoof").absolutePath
        // The last argument is our own pid: arpspoof watches it and restores
        // the victim by itself if this app dies before sending a SIGTERM.
        val cmd = "$binary ${info.iface} ${host.ip} ${host.mac} " +
            "${info.gatewayIp} $gatewayMac ${Process.myPid()}"
        val pid = RootExecutor.startDetached(cmd)
        if (pid <= 0 || !pidAlive(pid)) return@withContext false
        victims[host.ip] = Victim(
            ip = host.ip,
            mac = host.mac,
            pid = pid,
            iface = info.iface,
            gatewayIp = info.gatewayIp,
            gatewayMac = gatewayMac,
        )
        syncActive()
        true
    }

    fun unkill(host: Host) {
        victims.remove(host.ip)?.let {
            RootExecutor.terminate(it.pid)
            syncActive()
        }
    }

    fun unkillAll() {
        victims.values.forEach { RootExecutor.terminate(it.pid) }
        val hadAny = victims.isNotEmpty()
        victims.clear()
        if (hadAny) syncActive()
    }

    /**
     * Stop spoofers we cannot account for. NOT run automatically: startup
     * recovery prefers adoption via [reconcile], which preserves the user's
     * ongoing blocks instead of tearing them down.
     */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        val bin = File(File(context.filesDir, "bin"), "arpspoof").absolutePath
        RootExecutor.exec("pkill -TERM -f '$bin'", timeoutMs = 5000)
    }

    /**
     * Poll all active spoofer pids in one su round-trip every few seconds.
     * A spoofer that died unexpectedly (SIGKILL via phantom killer, LMK,
     * crash) never ran its own restore, so fire a corrective burst now and
     * report the victim as unblocked.
     */
    fun startWatchdog(scope: CoroutineScope) {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5000)
                val snapshot = victims.values.toList()
                if (snapshot.isEmpty()) continue
                val dead = findDeadPids(snapshot.map { it.pid })
                for (victim in snapshot.filter { it.pid in dead }) {
                    victims.remove(victim.ip)
                    rescue(victim)
                    syncActive()
                    _deaths.emit(victim.ip)
                }
            }
        }
    }

    /**
     * Adopt spoofers still running from an earlier session that the app-pid
     * watchdog could not stop -- typically because the old app pid was
     * recycled to another process, so the spoofer believes its owner lives.
     * Each spoofer's cmdline is self-describing
     * (iface ip mac gw_ip gw_mac app_pid), so no extra state is needed.
     *
     * @return hosts adopted as blocked; empty if everything is accounted for
     */
    suspend fun reconcile(): List<Host> = withContext(Dispatchers.IO) {
        val result = RootExecutor.exec(
            "for p in \$(pgrep -f 'files/bin/arpspoof'); do " +
                "echo \"PID=\$p\"; tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null; echo; done",
            timeoutMs = 5000,
        )
        data class Rec(val pid: Int, val iface: String, val ip: String, val mac: String,
                       val gwIp: String, val gwMac: String)
        val found = mutableListOf<Rec>()
        var currentPid = -1
        for (raw in result.stdout) {
            val line = raw.trim()
            if (line.startsWith("PID=")) {
                currentPid = line.removePrefix("PID=").toIntOrNull() ?: -1
                continue
            }
            if (currentPid <= 0 || line.isEmpty()) continue
            // cmdline tokens: <binary> <iface> <ip> <mac> <gw_ip> <gw_mac> [app_pid]
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            val recPid = currentPid
            currentPid = -1
            // Transient rescue bursts (--restore-only) exit within seconds;
            // adopting one would instantly look like a spoofer death.
            if (tokens.size < 6 || !tokens[0].endsWith("/arpspoof") ||
                tokens.contains("--restore-only")
            ) continue
            val (iface, ip, mac, gwIp, gwMac) = tokens.subList(1, 6)
            found.add(Rec(recPid, iface, ip, mac, gwIp, gwMac))
        }
        // One manager per victim IP: adopt the first record of each group;
        // any duplicate spoofer for the same IP is redundant -- terminate
        // it gracefully so it stops double-poisoning and repairs on exit.
        val adopted = mutableListOf<Host>()
        for ((_, group) in found.groupBy { it.ip }) {
            val keep = group.first()
            val existing = victims[keep.ip]
            if (existing == null) {
                victims[keep.ip] = Victim(
                    ip = keep.ip, mac = keep.mac, pid = keep.pid, iface = keep.iface,
                    gatewayIp = keep.gwIp, gatewayMac = keep.gwMac,
                )
                adopted.add(Host(ip = keep.ip, mac = keep.mac, isKilled = true))
                group.drop(1).forEach { RootExecutor.terminate(it.pid) }
            } else {
                // already tracked: every other process for this IP is a stray
                group.forEach { if (it.pid != existing.pid) RootExecutor.terminate(it.pid) }
            }
        }
        if (adopted.isNotEmpty()) syncActive()
        adopted
    }

    private suspend fun findDeadPids(pids: List<Int>): Set<Int> =
        withContext(Dispatchers.IO) {
            val list = pids.joinToString(" ")
            // /proc existence, not kill -0: a su hiccup must read as
            // "inconclusive" (keep the victim marked), never as death.
            val cmd = "for p in $list; do [ -d /proc/\$p ] || echo \$p; done"
            val result = RootExecutor.exec(cmd)
            android.util.Log.d(
                "WifiKill",
                "findDeadPids pids=$list rc=${result.exitCode} out=${result.stdout} dead-parsed",
            )
            if (result.exitCode != 0) return@withContext emptySet()
            result.stdout.mapNotNull { it.trim().toIntOrNull() }.toSet()
        }

    /** Best-effort one-shot ARP repair for a victim left poisoned. */
    private suspend fun rescue(victim: Victim) {
        runCatching {
            val binary = BinaryDeployer.deploy(context, "arpspoof").absolutePath
            val cmd = "$binary --restore-only ${victim.iface} ${victim.ip} " +
                "${victim.mac} ${victim.gatewayIp} ${victim.gatewayMac}"
            RootExecutor.exec(cmd, timeoutMs = 8000)
        }
    }

    private suspend fun pidAlive(pid: Int): Boolean =
        RootExecutor.exec("[ -d /proc/$pid ]").exitCode == 0
}
