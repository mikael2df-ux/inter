package com.xiaomi.xms.wearable.demo.ui.message

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Собирает состояние телефона и формирует JSON для отправки на браслет.
 * Формат:
 * { "type": "status", "battery": 83, "network": "Wi-Fi Home", "ip": "85.140.23.11", "ts": 1703... }
 */
object PhoneStatusHelper {

    @Volatile
    private var cachedIp: String = "--"

    /**
     * Собирает статус. Если [forceRefreshIp] true — делает блокирующий HTTP-запрос
     * за внешним IP. Иначе возвращает закэшированный.
     */
    fun collectSync(context: Context, forceRefreshIp: Boolean = false): JSONObject {
        if (forceRefreshIp || cachedIp == "--") {
            cachedIp = fetchExternalIp()
        }
        return buildJson(
            battery = readBatteryPct(context),
            network = readNetwork(context),
            ip = cachedIp
        )
    }

    /**
     * Асинхронная версия: делает запрос за IP в отдельном потоке и вызывает [onReady] с JSON.
     */
    fun collectAsync(context: Context, onReady: (JSONObject) -> Unit) {
        val ctx = context.applicationContext
        thread(isDaemon = true) {
            val ip = fetchExternalIp()
            cachedIp = ip
            val json = buildJson(
                battery = readBatteryPct(ctx),
                network = readNetwork(ctx),
                ip = ip
            )
            onReady(json)
        }
    }

    private fun buildJson(battery: Int, network: String, ip: String): JSONObject =
        JSONObject()
            .put("type", "status")
            .put("battery", battery)
            .put("network", network)
            .put("ip", ip)
            .put("ts", System.currentTimeMillis())

    private fun readBatteryPct(context: Context): Int {
        // BatteryManager.getIntProperty доступен с API 21
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct in 0..100) return pct

        // Fallback через broadcast
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun readNetwork(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "none"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(nw) ?: return "none"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiName(context)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> "net"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return "none"
            @Suppress("DEPRECATION")
            return info.typeName ?: "net"
        }
    }

    private fun wifiName(context: Context): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "Wi-Fi"
        @Suppress("DEPRECATION")
        val ssid = wifi.connectionInfo?.ssid ?: return "Wi-Fi"
        // На Android 10+ ssid будет <unknown ssid> без ACCESS_FINE_LOCATION — это ожидаемо.
        val clean = ssid.trim('"')
        return if (clean.isBlank() || clean.contains("unknown", true)) "Wi-Fi" else clean
    }

    private fun fetchExternalIp(): String {
        val urls = arrayOf(
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://ifconfig.me/ip"
        )
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (body.isNotEmpty()) return body
                }
                conn.disconnect()
            } catch (_: Exception) { /* try next */ }
        }
        return "offline"
    }
}
