package com.smsbridge.gateway.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.smsbridge.gateway.data.SmsRequest
import com.smsbridge.gateway.data.SmsResponse
import com.smsbridge.gateway.server.SmsHttpServer
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SmsDispatcher(private val context: Context) {

    private val simManager = SimManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("DEPRECATION")
    private fun getSmsManager(subscriptionId: Int): SmsManager {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
            else -> {
                SmsManager.getDefault()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSms(request: SmsRequest): SmsResponse {
        val recipient = request.getTargetRecipient()?.trim()
        val message = request.message
        val trackingId = request.trackingId ?: "sms_${UUID.randomUUID().toString().take(12)}"
        val simSlot = request.simSlot

        if (recipient.isNullOrBlank()) {
            return SmsResponse(
                success = false,
                trackingId = trackingId,
                status = "NOT_SENT",
                error = "Recipient number cannot be empty"
            )
        }

        if (message.isNullOrBlank()) {
            return SmsResponse(
                success = false,
                trackingId = trackingId,
                status = "NOT_SENT",
                error = "Message content cannot be empty"
            )
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            return SmsResponse(
                success = false,
                trackingId = trackingId,
                status = "NOT_SENT",
                error = "SEND_SMS permission not granted on Android device"
            )
        }

        return try {
            val subId = simManager.getSubscriptionIdForSlot(simSlot)
            val smsManager = getSmsManager(subId)
            val simSlots = simManager.getAvailableSimSlots()
            val currentSim = simSlots.find { it.slotIndex == simSlot }
            val carrierName = currentSim?.carrier ?: "SIM ${simSlot + 1}"

            val parts = smsManager.divideMessage(message)
            val partsCount = parts.size

            val sentActionBase = "com.smsbridge.gateway.SMS_SENT_${trackingId}"
            val deliveredActionBase = "com.smsbridge.gateway.SMS_DELIVERED_${trackingId}"

            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val latch = CountDownLatch(partsCount)
            var failureReason: String? = null
            val sentReceivers = mutableListOf<BroadcastReceiver>()
            val deliveredCount = AtomicInteger(0)

            // Setup Sent and Delivery receivers for each segment
            for (i in 0 until partsCount) {
                val partSentAction = "${sentActionBase}_$i"
                val partDeliveredAction = "${deliveredActionBase}_$i"

                val sentPi = PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(partSentAction).setPackage(context.packageName),
                    flags
                )
                val deliveryPi = PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(partDeliveredAction).setPackage(context.packageName),
                    flags
                )

                sentIntents.add(sentPi)
                deliveryIntents.add(deliveryPi)

                // Register Sent Receiver
                val sentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        try {
                            val code = resultCode
                            if (code != Activity.RESULT_OK) {
                                failureReason = formatSmsErrorCode(code)
                            }
                        } finally {
                            latch.countDown()
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        }
                    }
                }
                sentReceivers.add(sentReceiver)
                ContextCompat.registerReceiver(
                    context,
                    sentReceiver,
                    IntentFilter(partSentAction),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )

                // Register Delivery Receiver
                val deliveryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        try {
                            if (resultCode == Activity.RESULT_OK) {
                                val current = deliveredCount.incrementAndGet()
                                if (current >= partsCount) {
                                    SmsHttpServer.updateLogStatus(trackingId, "DELIVERED")
                                }
                            }
                        } finally {
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    deliveryReceiver,
                    IntentFilter(partDeliveredAction),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )

                // Automatically unregister delivery listener after 10 minutes if carrier never delivers
                scope.launch {
                    delay(10 * 60 * 1000L)
                    try { context.unregisterReceiver(deliveryReceiver) } catch (_: Exception) {}
                }
            }

            // Dispatch message to Android cellular radio
            if (partsCount > 1) {
                smsManager.sendMultipartTextMessage(
                    recipient,
                    null,
                    parts,
                    sentIntents,
                    deliveryIntents
                )
            } else {
                smsManager.sendTextMessage(
                    recipient,
                    null,
                    message,
                    sentIntents[0],
                    deliveryIntents[0]
                )
            }

            // Synchronously wait for carrier network confirmation (up to 5 seconds)
            val completedInTime = latch.await(5000, TimeUnit.MILLISECONDS)

            if (!completedInTime && failureReason == null) {
                // Radio buffer took longer than 5s; cleanup any uncalled sent receivers
                sentReceivers.forEach {
                    try { context.unregisterReceiver(it) } catch (_: Exception) {}
                }
            }

            if (failureReason != null) {
                SmsResponse(
                    success = false,
                    trackingId = trackingId,
                    status = "NOT_SENT",
                    partsCount = partsCount,
                    simSlot = simSlot,
                    simCarrier = carrierName,
                    error = failureReason
                )
            } else {
                SmsResponse(
                    success = true,
                    trackingId = trackingId,
                    status = "SENT",
                    partsCount = partsCount,
                    simSlot = simSlot,
                    simCarrier = carrierName,
                    error = null
                )
            }
        } catch (e: Exception) {
            SmsResponse(
                success = false,
                trackingId = trackingId,
                status = "NOT_SENT",
                simSlot = simSlot,
                error = e.message ?: "Failed to dispatch SMS via cellular radio"
            )
        }
    }

    private fun formatSmsErrorCode(code: Int): String {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier error: Destination unreachable or rejected by network"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Carrier error: Cellular radio is off (Airplane mode)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Carrier error: Null PDU generated by radio"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "Carrier error: No cellular network coverage"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Carrier error: SMS dispatch rate limit exceeded"
            else -> "Carrier rejection: Error code $code"
        }
    }
}
