package com.mauri.appscannertokens

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)

    // Estados reactivos para la UI
    var apiKey by mutableStateOf("")
    var secretKey by mutableStateOf("")
    var apalancamiento by mutableStateOf("20")
    var roiMinimo by mutableStateOf("5.0")
    var timeframeSeleccionado by mutableStateOf("3m") // Por defecto como tu Service

    // Opciones extras basadas en tu Service
    var billeteraManual by mutableStateOf("20.0")
    var tamanoPosPct by mutableStateOf("30")
    var perdidaMaxPct by mutableStateOf("5.0")

    // Lista de temporalidades soportadas (Binance Futures estándar)
    val opcionesTimeframe = listOf("1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d")

    init {
        // Cargar valores guardados al iniciar
        apiKey = prefs.getString(PrefKeys.API_KEY, "") ?: ""
        secretKey = prefs.getString(PrefKeys.SECRET_KEY, "") ?: ""

        // Convertimos números a String para los TextFields
        apalancamiento = prefs.getFloat(PrefKeys.APALANCAMIENTO, 20f).toInt().toString()
        roiMinimo = prefs.getFloat(PrefKeys.ROI_MINIMO, 5f).toString()
        timeframeSeleccionado = prefs.getString(PrefKeys.TIMEFRAME, "3m") ?: "3m"

        billeteraManual = prefs.getFloat(PrefKeys.BILLETERA_MANUAL, 20f).toString()
        tamanoPosPct = prefs.getFloat(PrefKeys.TAMANO_POS_PCT, 30f).toInt().toString()
        perdidaMaxPct = prefs.getFloat(PrefKeys.PERDIDA_MAX_PCT, 5f).toString()
    }

    fun guardarConfiguracion(): Boolean {
        return try {
            prefs.edit {
                putString(PrefKeys.API_KEY, apiKey.trim())
                putString(PrefKeys.SECRET_KEY, secretKey.trim())

                // Validación básica antes de guardar números
                putFloat(PrefKeys.APALANCAMIENTO, apalancamiento.toFloatOrNull() ?: 20f)
                putFloat(PrefKeys.ROI_MINIMO, roiMinimo.toFloatOrNull() ?: 5f)
                putString(PrefKeys.TIMEFRAME, timeframeSeleccionado)

                putFloat(PrefKeys.BILLETERA_MANUAL, billeteraManual.toFloatOrNull() ?: 20f)
                putFloat(PrefKeys.TAMANO_POS_PCT, tamanoPosPct.toFloatOrNull() ?: 30f)
                putFloat(PrefKeys.PERDIDA_MAX_PCT, perdidaMaxPct.toFloatOrNull() ?: 5f)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}