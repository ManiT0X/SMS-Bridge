package com.smsbridge.gateway.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.smsbridge.gateway.data.SimSlotInfo

class SimManager(private val context: Context) {

    private val subscriptionManager: SubscriptionManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        } else {
            null
        }
    }

    private val telephonyManager: TelephonyManager? by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }

    @SuppressLint("MissingPermission")
    fun getAvailableSimSlots(): List<SimSlotInfo> {
        val simList = mutableListOf<SimSlotInfo>()

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
            val activeList: List<SubscriptionInfo>? = try {
                subscriptionManager?.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                null
            }

            if (!activeList.isNullOrEmpty()) {
                for (info in activeList) {
                    val carrierName = info.carrierName?.toString()
                        ?: info.displayName?.toString()
                        ?: "SIM ${info.simSlotIndex + 1}"
                    
                    simList.add(
                        SimSlotInfo(
                            slotIndex = info.simSlotIndex,
                            carrier = carrierName,
                            subscriptionId = info.subscriptionId,
                            isActive = true,
                            displayName = info.displayName?.toString() ?: carrierName
                        )
                    )
                }
            }
        }

        // Fallback for devices with 1 SIM or before permissions granted
        if (simList.isEmpty()) {
            val networkOperatorName = telephonyManager?.networkOperatorName
            val simOperatorName = telephonyManager?.simOperatorName
            val carrier = when {
                !networkOperatorName.isNullOrBlank() -> networkOperatorName
                !simOperatorName.isNullOrBlank() -> simOperatorName
                else -> "Default Cellular SIM"
            }

            simList.add(
                SimSlotInfo(
                    slotIndex = 0,
                    carrier = carrier,
                    subscriptionId = SubscriptionManager.getDefaultSubscriptionId(),
                    isActive = true,
                    displayName = "Primary SIM"
                )
            )
        }

        return simList.sortedBy { it.slotIndex }
    }

    fun getSubscriptionIdForSlot(slotIndex: Int): Int {
        val slots = getAvailableSimSlots()
        val match = slots.find { it.slotIndex == slotIndex }
        return match?.subscriptionId ?: SubscriptionManager.getDefaultSubscriptionId()
    }
}
