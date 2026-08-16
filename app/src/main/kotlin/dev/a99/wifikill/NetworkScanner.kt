package dev.a99.wifikill

import android.content.Context
import dev.a99.wifikill.model.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface

class NetworkScanner(private val context: Context) {

    data class NetworkInfo(val gatewayIp: String, val prefixLen: Int, val iface: String)

    fun getNetworkInfo(): NetworkInfo {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val dhcp = wm.dhcpInfo
        val gateway = Integer.reverseBytes(dhcp.gateway).toIp()
        val prefix = Integer.bitCount(dhcp.netmask)
        val iface = findWifiInterface() ?: "wlan0"
        return NetworkInfo(gateway, prefix, iface)
    }

    private fun findWifiInterface(): String? =
        if (wifiIfaceUp()) guessWifiIfaceName()
        else guessIfaceFromProc()

    private fun wifiIfaceUp(): Boolean = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
                ni.isUp && !ni.isLoopback && ni.name.startsWith("wlan")
            }
        }.getOrDefault(false)

    private fun guessWifiIfaceName(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.first { ni ->
            ni.isUp && !ni.isLoopback && ni.name.startsWith("wlan")
        }?.name ?: "wlan0"
    }.getOrDefault("wlan0")

    private fun guessIfaceFromProc(): String =
        runCatching {
            listOf("wlan0", "wlan1", "wlan2", "eth0", "ap0")
                .firstOrNull { name ->
                    File("/sys/class/net/$name/operstate")
                        .takeIf { it.exists() }
                        ?.readText()?.trim() == "up"
                } ?: "wlan0"
        }.getOrDefault("wlan0")

    private fun Int.toIp(): String =
        "${(this shr 24) and 0xff}.${(this shr 16) and 0xff}.${(this shr 8) and 0xff}.${this and 0xff}"

    private suspend fun deployBinary(name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val dest = File(dir, name)
        val assetHash = context.assets.open(name).use { it.readBytes().contentHashCode() }
        if (!dest.exists() || dest.length() == 0L ||
            runCatching { dest.readBytes().contentHashCode() }.getOrDefault(-1) != assetHash
        ) {
            context.assets.open(name).use { `in` ->
                FileOutputStream(dest).use { out -> `in`.copyTo(out) }
            }
        }
        if (!dest.canExecute()) {
            RootExecutor.exec("chmod 755 \"${dest.absolutePath}\"")
        }
        dest
    }

    fun scan(): Flow<Host> = channelFlow {
        val info = getNetworkInfo()
        val binary = deployBinary("arpscan")
        val cmd = "${binary.absolutePath} ${info.iface} ${info.gatewayIp} ${info.prefixLen}"
        val proc = RootExecutor.startPersistent(cmd)
        val reader = proc.childOutput()
        if (reader != null) {
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val mac = parts[1]
                    if (mac != "00:00:00:00:00:00") {
                        send(Host(ip = parts[0], mac = mac))
                    }
                }
            }
        } else {
            throw IOException("no output stream from arpscan")
        }
        proc.kill()
    }
}