package com.mauri.appscannertokens

import android.content.Context
import com.google.gson.Gson

class ConfigRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): UserConfig {
        val json = prefs.getString(KEY_CONFIG, null)
        val config = if (json != null) gson.fromJson(json, UserConfig::class.java) else UserConfig()
        return config.copy(riesgoMaximoBilletera = config.riesgoMaximoBilletera.coerceAtMost(RiskLimits.MAX_WALLET_RISK_PCT))
    }

    fun save(config: UserConfig) {
        val safeConfig = config.copy(
            riesgoMaximoBilletera = config.riesgoMaximoBilletera.coerceAtMost(RiskLimits.MAX_WALLET_RISK_PCT)
        )
        prefs.edit().putString(KEY_CONFIG, gson.toJson(safeConfig)).apply()
    }

    private companion object {
        const val PREFS_NAME = "AppScannerConfig"
        const val KEY_CONFIG = "config_data"
    }
}
