package com.smsbridge.gateway.data

import com.google.gson.annotations.SerializedName

data class SmsRequest(
    @SerializedName("to") val to: String?,
    @SerializedName("recipient") val recipient: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("sim_slot") val simSlot: Int = 0,
    @SerializedName("tracking_id") val trackingId: String? = null
) {
    fun getTargetRecipient(): String? = to ?: recipient
}

data class SmsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("tracking_id") val trackingId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("parts_count") val partsCount: Int = 1,
    @SerializedName("sim_slot") val simSlot: Int = 0,
    @SerializedName("sim_carrier") val simCarrier: String? = null,
    @SerializedName("error") val error: String? = null
)

data class BatteryInfo(
    @SerializedName("level") val level: Int,
    @SerializedName("is_charging") val isCharging: Boolean
)

data class WifiInfo(
    @SerializedName("ssid") val ssid: String,
    @SerializedName("ip") val ip: String
)

data class SimSlotInfo(
    @SerializedName("slot_index") val slotIndex: Int,
    @SerializedName("carrier") val carrier: String,
    @SerializedName("subscription_id") val subscriptionId: Int,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("display_name") val displayName: String
)

data class GatewayStats(
    @SerializedName("sent_count") val sentCount: Int,
    @SerializedName("failed_count") val failedCount: Int,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long
)

data class StatusResponse(
    @SerializedName("status") val status: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("battery") val battery: BatteryInfo,
    @SerializedName("wifi") val wifi: WifiInfo,
    @SerializedName("sim_slots") val simSlots: List<SimSlotInfo>,
    @SerializedName("stats") val stats: GatewayStats,
    @SerializedName("auth_enabled") val authEnabled: Boolean
)

data class ConfigPayload(
    @SerializedName("api_key") val apiKey: String?,
    @SerializedName("auth_enabled") val authEnabled: Boolean?,
    @SerializedName("default_sim_slot") val defaultSimSlot: Int?
)

data class LogEntry(
    val id: String,
    val timestamp: Long,
    val recipient: String,
    val message: String,
    val simSlot: Int,
    val status: String,
    val error: String? = null
)
