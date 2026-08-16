package dev.a99.wifikill

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.a99.wifikill.model.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkScanner(private val context: Context) {

    data class NetworkInfo(val gatewayIp: String, val prefixLen: Int, val iface: String)

    /**
     * Resolve the active Wi-Fi's gateway, prefix length and interface name from
     * [ConnectivityManager]/[LinkProperties].
     *
     * [android.net.wifi.WifiManager.dhcpInfo] is deprecated and returns all-zero
     * fields on Android 15+ (API 35+), so we must not rely on it. The default
     * route reported by netd carries the real gateway (which is not always the
     * ".1" address). The interface name and prefix come from the Wi-Fi link's
     * [android.net.LinkAddress].
     */
    fun getNetworkInfo(): NetworkInfo {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val lp = wifiLinkProperties(cm)

        val gateway = gateway(lp)

        val prefix = lp?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.prefixLength ?: 24

        val iface = lp?.interfaceName
            ?.takeIf { it.isNotBlank() }
            ?: findWifiInterface() ?: "wlan0"

        if (gateway == null) {
            throw IOException("could not determine Wi-Fi gateway (no default IPv4 route)")
        }

        return NetworkInfo(gateway, prefix, iface)
    }

    private fun wifiLinkProperties(cm: ConnectivityManager): LinkProperties? {
        val active = cm.activeNetwork
        if (active != null && isWifi(cm, active)) {
            return cm.getLinkProperties(active)
        }
        // Fall back to enumerating all networks so an active VPN over Wi-Fi
        // still resolves the underlying Wi-Fi link.
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { isWifi(cm, it) }
        return wifi?.let { cm.getLinkProperties(it) }
    }

    private fun isWifi(cm: ConnectivityManager, network: Network): Boolean =
        cm.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun gateway(lp: LinkProperties?): String? {
        val viaRoute = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        if (viaRoute != null) return viaRoute

        // Fallback to the DHCP server address (Android 13+), which is usually
        // the gateway even when the default-route gateway is not exposed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lp?.dhcpServerAddress?.hostAddress?.let { return it }
        }
        return null
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