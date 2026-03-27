package com.mauri.appscannertokens

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey by mutableStateOf(prefs.getString(PrefKeys.API_KEY, "") ?: "")
    var secretKey by mutableStateOf(prefs.getString(PrefKeys.SECRET_KEY, "") ?: "")
    var timeframe by mutableStateOf(prefs.getString(PrefKeys.TIMEFRAME, "3m") ?: "3m")
    var billeteraManual by mutableStateOf(prefs.getFloat(PrefKeys.BILLETERA_MANUAL, 20f).toString())
    var roiMinimo by mutableStateOf(prefs.getFloat(PrefKeys.ROI_MINIMO, 5f).toString())
    var apalancamiento by mutableStateOf(prefs.getFloat(PrefKeys.APALANCAMIENTO, 20f).toString())

    var tamanoPosPct by mutableStateOf(prefs.getFloat(PrefKeys.TAMANO_POS_PCT, 30f).toString())
    var perdidaMaxPct by mutableStateOf(prefs.getFloat(PrefKeys.PERDIDA_MAX_PCT, 5f).toString())
    var tipoMargen by mutableStateOf(prefs.getString(PrefKeys.TIPO_MARGEN, "ISOLATED") ?: "ISOLATED")

    var validandoApi by mutableStateOf(false)
    var mensajeValidacion by mutableStateOf("")

    fun guardarConfiguracion() {
        prefs.edit {
            putString(PrefKeys.API_KEY, apiKey.trim())
            putString(PrefKeys.SECRET_KEY, secretKey.trim())
            putString(PrefKeys.TIMEFRAME, timeframe.trim())
            putFloat(PrefKeys.BILLETERA_MANUAL, billeteraManual.toFloatOrNull() ?: 20f)
            putFloat(PrefKeys.ROI_MINIMO, roiMinimo.toFloatOrNull() ?: 5f)
            putFloat(PrefKeys.APALANCAMIENTO, apalancamiento.toFloatOrNull() ?: 20f)

            putFloat(PrefKeys.TAMANO_POS_PCT, tamanoPosPct.toFloatOrNull() ?: 30f)
            putFloat(PrefKeys.PERDIDA_MAX_PCT, perdidaMaxPct.toFloatOrNull() ?: 5f)
            putString(PrefKeys.TIPO_MARGEN, tipoMargen)
        }
    }

    fun validarCredenciales() {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            mensajeValidacion = "⚠️ Ingresa ambas claves primero"
            return
        }
        validandoApi = true
        mensajeValidacion = "Validando conexión con Binance..."

        Thread {
            try {
                val client = OkHttpClient()
                val timestamp = System.currentTimeMillis()
                val queryString = "timestamp=$timestamp"

                val sha256HMAC = Mac.getInstance("HmacSHA256")
                val secretKeySpec = SecretKeySpec(secretKey.trim().toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
                sha256HMAC.init(secretKeySpec)
                val hash = sha256HMAC.doFinal(queryString.toByteArray(StandardCharsets.UTF_8))
                val signature = hash.joinToString("") { "%02x".format(it) }

                val url = "https://fapi.binance.com/fapi/v2/balance?$queryString&signature=$signature"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiKey.trim())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        mensajeValidacion = "✅ API Validada: Conectado a Binance"
                        guardarConfiguracion() // Guardamos si es correcta
                    } else {
                        mensajeValidacion = "❌ Error: API Key o Secret inválidos"
                    }
                }
            } catch (e: Exception) {
                mensajeValidacion = "❌ Error de red: ${e.message}"
            } finally {
                validandoApi = false
            }
        }.start()
    }
}