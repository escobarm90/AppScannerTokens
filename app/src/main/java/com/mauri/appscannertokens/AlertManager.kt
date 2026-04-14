package com.mauri.appscannertokens

import android.content.Context
import android.media.RingtoneManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AlertData(
    val symbol: String,
    val senal: String,
    val precio: Double,
    val tp: Double,
    val sl: Double,
    val velasEst: Int = 2,
    val timeframe: String,
    val timestamp: Long = System.currentTimeMillis()
)

object AlertManager {
    private val _alertas = MutableStateFlow<List<AlertData>>(emptyList())
    val alertas: StateFlow<List<AlertData>> = _alertas.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val gson = Gson()
    private const val PREFS_NAME = "AppScannerAlerts"
    private const val KEY_ALERTAS = "alertas_guardadas"

    // NUEVO: Carga el historial al abrir la app
    fun inicializar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ALERTAS, null)
        if (json != null) {
            val type = object : TypeToken<List<AlertData>>() {}.type
            val guardadas: List<AlertData> = gson.fromJson(json, type)
            _alertas.value = guardadas
        }
    }

    // NUEVO: Requiere Context para guardar y sonar
    fun agregarAlerta(context: Context, alerta: AlertData) {
        _alertas.update { actual ->
            val nuevaLista = actual.toMutableList()
            nuevaLista.add(0, alerta)

            // LÍMITE ESTRICTO DE 5 TARJETAS (Se pisan las antiguas)
            val listaLimitada = if (nuevaLista.size > 5) nuevaLista.take(5) else nuevaLista

            // Guardamos en memoria física
            guardarEnMemoria(context, listaLimitada)

            listaLimitada
        }
        reproducirSonido(context)
    }

    fun removerAlerta(context: Context, alerta: AlertData) {
        _alertas.update { actual ->
            val nuevaLista = actual.filter { it != alerta }
            guardarEnMemoria(context, nuevaLista)
            nuevaLista
        }
    }

    private fun guardarEnMemoria(context: Context, lista: List<AlertData>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ALERTAS, gson.toJson(lista)).apply()
    }

    private fun reproducirSonido(context: Context) {
        try {
            // Reproduce el sonido de notificación predeterminado de tu S23 Ultra
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun agregarLog(mensaje: String) {
        _logs.update { actual ->
            val nuevaLista = actual.toMutableList()
            nuevaLista.add(0, mensaje)
            if (nuevaLista.size > 100) nuevaLista.take(100) else nuevaLista
        }
    }
}