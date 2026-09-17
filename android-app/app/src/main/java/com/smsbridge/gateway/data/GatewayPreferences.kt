package com.smsbridge.gateway.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GatewayPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sms_bridge_prefs", Context.MODE_PRIVATE)

    init {
        _sentCountFlow.value = prefs.getInt(KEY_SENT_COUNT, 0)
        _themeFlow.value = prefs.getBoolean(KEY_DARK_THEME, true)
    }

    var isDarkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()
            _themeFlow.value = value
        }

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
            notifyConfigChanged()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
            notifyConfigChanged()
        }

    var isAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTH_ENABLED, value).apply()
            notifyConfigChanged()
        }

    var defaultSimSlot: Int
        get() = prefs.getInt(KEY_DEFAULT_SIM_SLOT, 0)
        set(value) {
            prefs.edit().putInt(KEY_DEFAULT_SIM_SLOT, value).apply()
            notifyConfigChanged()
        }

    var sentCount: Int
        get() = prefs.getInt(KEY_SENT_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_SENT_COUNT, value).apply()
            _sentCountFlow.value = value
        }

    var failedCount: Int
        get() = prefs.getInt(KEY_FAILED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_COUNT, value).apply()

    fun incrementSent() {
        sentCount += 1
    }

    fun incrementFailed() {
        failedCount += 1
    }

    private fun notifyConfigChanged() {
        _configUpdateFlow.value = System.currentTimeMillis()
    }

    companion object {
        private const val KEY_DARK_THEME = "pref_dark_theme"
        private const val KEY_PORT = "pref_port"
        private const val KEY_API_KEY = "pref_api_key"
        private const val KEY_AUTH_ENABLED = "pref_auth_enabled"
        private const val KEY_DEFAULT_SIM_SLOT = "pref_default_sim_slot"
        private const val KEY_SENT_COUNT = "pref_sent_count"
        private const val KEY_FAILED_COUNT = "pref_failed_count"

        private val _themeFlow = MutableStateFlow(true)
        val themeFlow = _themeFlow.asStateFlow()

        private val _configUpdateFlow = MutableStateFlow(System.currentTimeMillis())
        val configUpdateFlow = _configUpdateFlow.asStateFlow()

        private val _sentCountFlow = MutableStateFlow(0)
        val sentCountFlow = _sentCountFlow.asStateFlow()
    }
}
