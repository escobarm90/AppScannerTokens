package com.mauri.appscannertokens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    // Esto es el equivalente a tu "config_bot.json" en Android
    private val prefs = application.getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Variable que mantiene el estado actual de la configuración para la pantalla
    private val _configState = MutableStateFlow(cargarConfiguracion())
    val configState: StateFlow<UserConfig> = _configState.asStateFlow()

    // Función que lee el JSON guardado
    private fun cargarConfiguracion(): UserConfig {
        val jsonGuardado = prefs.getString("config_data", null)
        return if (jsonGuardado != null) {
            gson.fromJson(jsonGuardado, UserConfig::class.java)
        } else {
            UserConfig() // Si no hay nada guardado, carga los valores por defecto
        }
    }

    // Función que guarda los cambios que el usuario hace en la pantalla
    fun guardarConfiguracion(nuevaConfig: UserConfig) {
        val jsonParaGuardar = gson.toJson(nuevaConfig)
        prefs.edit().putString("config_data", jsonParaGuardar).apply()
        _configState.value = nuevaConfig
    }
}