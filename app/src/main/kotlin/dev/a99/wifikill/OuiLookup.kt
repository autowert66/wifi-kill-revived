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

    fun lookup(mac: String): String? {
        val prefix = mac
            .replace("-", ":")
            .trim()
            .let { runCatching { it.substring(0, 8) }.getOrDefault("") }
            .uppercase()
        if (prefix.length != 8 || !prefix.all { c -> c.isDigit() || c in 'A'..'F' }) return null
        return cache[prefix]
    }

    fun isReady(): Boolean = cache.isNotEmpty()
}