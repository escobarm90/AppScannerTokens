package com.mauri.appscannertokens.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.mauri.appscannertokens.domain.model.UserConfig

class ConfigRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "AppScannerSecureConfig",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    fun load(): UserConfig {
        val json = prefs.getString("secure_config_data", null)
        return if (!json.isNullOrEmpty()) {
            gson.fromJson(json, UserConfig::class.java)
        } else {
            UserConfig()
        }
    }

    fun save(config: UserConfig) {
        prefs.edit().putString("secure_config_data", gson.toJson(config)).apply()
    }
}