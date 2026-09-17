package com.smsbridge.gateway.telephony

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.smsbridge.gateway.data.BatteryInfo
import com.smsbridge.gateway.data.WifiInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object SystemMetrics {

    fun getBatteryInfo(context: Context): BatteryInfo {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, filter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryInfo(level = batteryPct, isCharging = isCharging)
    }

    fun getWifiInfo(context: Context): WifiInfo {
        val ip = getLocalIpAddress()
        var ssid = if (ip != "127.0.0.1") "Connected" else "Offline"

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

            if (isWifi || ip != "127.0.0.1") {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val connectionInfo = wifiManager?.connectionInfo
                if (connectionInfo != null) {
                    var rawSsid = connectionInfo.ssid
                    if (rawSsid != null && rawSsid.startsWith("\"") && rawSsid.endsWith("\"")) {
                        rawSsid = rawSsid.substring(1, rawSsid.length - 1)
                    }
                    if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>" && rawSsid != "0x") {
                        ssid = rawSsid
                    } else {
                        ssid = "Connected"
                    }
                } else {
                    ssid = "Connected"
                }
            } else {
                ssid = "Offline"
            }
        } catch (_: Exception) {}

        return WifiInfo(ssid = ssid, ip = ip)
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("eth") || intf.name.contains("ap")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
            // Fallback: check all interfaces
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}
