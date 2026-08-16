package dev.a99.wifikill

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object OuiLookup {

    private val cache = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (cache.isNotEmpty()) return
        try {
            val text = context.assets.open("oui.json")
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                cache[k] = json.getString(k)
            }
        } catch (_: Exception) {
        }
    }

    fun lookup(mac: String): String? = matchPrefix(cache, mac)

    fun isReady(): Boolean = cache.isNotEmpty()

    /**
     * Strip separators and lowercase hex, returning the 12 uppercase hex digits
     * of a well-formed MAC, or null if the input is not a full MAC.
     */
    internal fun normalizeMac(mac: String): String? {
        val hex = mac.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return hex.uppercase().takeIf { it.length == 12 }
    }

    /**
     * Longest-prefix match against a map of OUI prefixes. Prefixes are hex
     * digit strings of length 9 (MA-S /36), 7 (MA-M /28) or 6 (MA-L /24).
     */
    internal fun matchPrefix(oui: Map<String, String>, mac: String): String? {
        val hex = normalizeMac(mac) ?: return null
        for (len in intArrayOf(9, 7, 6)) {
            oui[hex.substring(0, len)]?.let { return it }
        }
        return null
    }
}
