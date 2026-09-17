package com.smsbridge.gateway.server

import android.content.Context
import com.google.gson.Gson
import com.smsbridge.gateway.data.*
import com.smsbridge.gateway.telephony.SimManager
import com.smsbridge.gateway.telephony.SmsDispatcher
import com.smsbridge.gateway.telephony.SystemMetrics
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SmsHttpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val prefs = GatewayPreferences(context)
    private val simManager = SimManager(context)
    private val smsDispatcher = SmsDispatcher(context)
    private val startTime = System.currentTimeMillis()

    companion object {
        private const val MAX_LOGS = 100
        private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
        val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

        val inMemoryLogs: List<LogEntry> get() = _logsFlow.value

        @Synchronized
        fun addLog(entry: LogEntry) {
            val current = _logsFlow.value.toMutableList()
            if (current.size >= MAX_LOGS) {
                current.removeAt(current.size - 1)
            }
            current.add(0, entry)
            _logsFlow.value = current
        }

        @Synchronized
        fun updateLogStatus(trackingId: String, newStatus: String, error: String? = null) {
            val current = _logsFlow.value.toMutableList()
            val index = current.indexOfFirst { it.id == trackingId }
            if (index != -1) {
                val old = current[index]
                current[index] = old.copy(status = newStatus, error = error ?: old.error)
                _logsFlow.value = current
            }
        }

        fun clearLogs() {
            _logsFlow.value = emptyList()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        // Handle CORS Preflight
        if (Method.OPTIONS == method) {
            return createJsonResponse(Response.Status.OK, mapOf("status" to "OK")).apply {
                addCorsHeaders(this)
            }
        }

        // Authenticate request if enabled
        if (prefs.isAuthEnabled) {
            if (!isAuthorized(session)) {
                return createJsonResponse(
                    Response.Status.UNAUTHORIZED,
                    mapOf("success" to false, "error" to "Unauthorized: Missing or invalid API Key")
                ).apply { addCorsHeaders(this) }
            }
        }

        val response = try {
            when {
                Method.POST == method && uri == "/send-sms" -> handleSendSms(session)
                Method.GET == method && uri == "/api/status" -> handleGetStatus()
                Method.POST == method && uri == "/api/config" -> handlePostConfig(session)
                Method.GET == method && uri == "/api/logs" -> handleGetLogs()
                else -> createJsonResponse(
                    Response.Status.NOT_FOUND,
                    mapOf("error" to "Endpoint not found", "uri" to uri)
                )
            }
        } catch (e: Exception) {
            createJsonResponse(
                Response.Status.INTERNAL_ERROR,
                mapOf("error" to (e.message ?: "Internal Server Error"))
            )
        }

        addCorsHeaders(response)
        return response
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val headers = session.headers
        val authHeader = headers["authorization"] ?: headers["Authorization"]
        val apiKeyHeader = headers["x-api-key"] ?: headers["X-API-Key"]

        val expectedKey = prefs.apiKey.trim()
        if (expectedKey.isEmpty()) return true

        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val token = authHeader.substring(7).trim()
            if (token == expectedKey) return true
        }

        if (apiKeyHeader != null && apiKeyHeader.trim() == expectedKey) {
            return true
        }

        return false
    }

    private fun handleSendSms(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val postData = map["postData"]

        if (postData.isNullOrBlank()) {
            return createJsonResponse(
                Response.Status.BAD_REQUEST,
                mapOf("success" to false, "error" to "Missing JSON request body")
            )
        }

        val smsRequest = try {
            gson.fromJson(postData, SmsRequest::class.java)
        } catch (e: Exception) {
            return createJsonResponse(
                Response.Status.BAD_REQUEST,
                mapOf("success" to false, "error" to "Malformed JSON: ${e.message}")
            )
        }

        val response = smsDispatcher.sendSms(smsRequest)

        if (response.success) {
            prefs.incrementSent()
            addLog(
                LogEntry(
                    id = response.trackingId ?: "N/A",
                    timestamp = System.currentTimeMillis(),
                    recipient = smsRequest.getTargetRecipient() ?: "",
                    message = smsRequest.message ?: "",
                    simSlot = response.simSlot,
                    status = response.status
                )
            )
            return createJsonResponse(Response.Status.OK, response)
        } else {
            prefs.incrementFailed()
            addLog(
                LogEntry(
                    id = response.trackingId ?: "N/A",
                    timestamp = System.currentTimeMillis(),
                    recipient = smsRequest.getTargetRecipient() ?: "",
                    message = smsRequest.message ?: "",
                    simSlot = response.simSlot,
                    status = response.status,
                    error = response.error
                )
            )
            return createJsonResponse(Response.Status.BAD_REQUEST, response)
        }
    }

    private fun handleGetStatus(): Response {
        val battery = SystemMetrics.getBatteryInfo(context)
        val wifi = SystemMetrics.getWifiInfo(context)
        val simSlots = simManager.getAvailableSimSlots()
        val uptimeSec = (System.currentTimeMillis() - startTime) / 1000

        val statusResponse = StatusResponse(
            status = "ONLINE",
            deviceModel = SystemMetrics.getDeviceModel(),
            battery = battery,
            wifi = wifi,
            simSlots = simSlots,
            stats = GatewayStats(
                sentCount = prefs.sentCount,
                failedCount = prefs.failedCount,
                uptimeSeconds = uptimeSec
            ),
            authEnabled = prefs.isAuthEnabled
        )

        return createJsonResponse(Response.Status.OK, statusResponse)
    }

    private fun handlePostConfig(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val postData = map["postData"]

        if (!postData.isNullOrBlank()) {
            val payload = gson.fromJson(postData, ConfigPayload::class.java)
            payload.apiKey?.let { prefs.apiKey = it }
            payload.authEnabled?.let { prefs.isAuthEnabled = it }
            payload.defaultSimSlot?.let { prefs.defaultSimSlot = it }
        }

        return createJsonResponse(
            Response.Status.OK,
            mapOf("success" to true, "message" to "Configuration updated successfully")
        )
    }

    private fun handleGetLogs(): Response {
        return createJsonResponse(Response.Status.OK, inMemoryLogs.toList())
    }

    private fun createJsonResponse(status: Response.IStatus, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")
    }
}
