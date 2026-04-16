package com.mauri.appscannertokens

import android.content.Context
import android.media.RingtoneManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AlertManager {
    private val _alertas = MutableStateFlow<List<TradingScannerService.AlertData>>(emptyList())
    val alertas: StateFlow<List<TradingScannerService.AlertData>> = _alertas.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val gson = Gson()
    private const val PREFS = "AlertasHistorial"

    fun inicializar(context: Context) {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", null)
        if (json != null) {
            val type = object : TypeToken<List<TradingScannerService.AlertData>>() {}.type
            _alertas.value = gson.fromJson(json, type)
        }
    }

    fun agregarAlerta(context: Context, alerta: TradingScannerService.AlertData) {
        _alertas.update { actual ->
            // Filtramos para que no se duplique la misma señal al mismo tiempo
            if (actual.isNotEmpty() && actual[0].symbol == alerta.symbol && actual[0].senal == alerta.senal) {
                return@update actual
            }

            val nuevaLista = actual.toMutableList()
            nuevaLista.add(0, alerta)

            // Límite estricto de 5 tarjetas
            val tope = if (nuevaLista.size > 5) nuevaLista.take(5) else nuevaLista

            // Guardado físico en el teléfono
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", gson.toJson(tope)).apply()
            tope
        }

        // Sonido de alerta
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri).play()
        } catch (e: Exception) {}
    }

    fun removerAlerta(context: Context, alerta: TradingScannerService.AlertData) {
        _alertas.update { actual ->
            val nueva = actual.filter { it != alerta }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", gson.toJson(nueva)).apply()
            nueva
        }
    }

    fun agregarLog(mensaje: String) {
        _logs.update { actual ->
            val lista = actual.toMutableList()
            lista.add(0, mensaje)
            if (lista.size > 100) lista.take(100) else lista
        }
    }
}