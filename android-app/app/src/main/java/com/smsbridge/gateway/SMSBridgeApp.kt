package com.smsbridge.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SMSBridgeApp : Application() {

    companion object {
        const val CHANNEL_ID = "sms_bridge_gateway_channel"
        lateinit var instance: SMSBridgeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
