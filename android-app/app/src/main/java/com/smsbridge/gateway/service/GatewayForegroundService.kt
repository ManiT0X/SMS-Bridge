package com.smsbridge.gateway.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.smsbridge.gateway.MainActivity
import com.smsbridge.gateway.R
import com.smsbridge.gateway.SMSBridgeApp
import com.smsbridge.gateway.data.GatewayPreferences
import com.smsbridge.gateway.server.SmsHttpServer
import com.smsbridge.gateway.telephony.SystemMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GatewayForegroundService : Service() {

    private var server: SmsHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var prefs: GatewayPreferences

    companion object {
        const val ACTION_START = "com.smsbridge.gateway.action.START"
        const val ACTION_STOP = "com.smsbridge.gateway.action.STOP"
        private const val NOTIFICATION_ID = 1001

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GatewayPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startGateway()
            ACTION_STOP -> stopGateway()
        }
        return START_STICKY
    }

    private fun startGateway() {
        if (_isServiceRunning.value) return

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isServiceRunning.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
                _isServiceRunning.value = true
            } catch (e2: Exception) {
                e2.printStackTrace()
                _isServiceRunning.value = false
                return
            }
        }

        try {
            acquireLocks()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            startHttpServer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopGateway() {
        try {
            stopHttpServer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            releaseLocks()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isServiceRunning.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun startHttpServer() {
        try {
            if (server != null) {
                server?.stop()
                server = null
            }
            val port = prefs.port
            server = SmsHttpServer(this, port)
            server?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopHttpServer() {
        try {
            server?.stop()
            server = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireLocks() {
        try {
            // WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SMSBridge::GatewayWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24h safety timeout
            }

            // WifiLock
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SMSBridge::GatewayWifiLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GatewayForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ip = try {
            SystemMetrics.getLocalIpAddress()
        } catch (_: Exception) {
            "127.0.0.1"
        }
        val port = prefs.port
        val url = "http://$ip:$port"

        return NotificationCompat.Builder(this, SMSBridgeApp.CHANNEL_ID)
            .setContentTitle("SMS Bridge Gateway Active")
            .setContentText("Listening on $url")
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
