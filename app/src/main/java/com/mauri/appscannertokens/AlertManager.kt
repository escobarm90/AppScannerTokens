package com.mauri.appscannertokens

import android.content.Context
import android.media.RingtoneManager
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AlertManager {
    private val _alertas = MutableStateFlow<List<AlertData>>(emptyList())
    val alertas: StateFlow<List<AlertData>> = _alertas.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val gson = Gson()
    private const val PREFS = "AlertasHistorial"

    fun inicializar(context: Context) {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", null)
        if (json != null) {
            val type = object : TypeToken<List<AlertData>>() {}.type
            _alertas.value = gson.fromJson(json, type)
        }
    }

    // Corregido: Ahora solo recibe AlertData
    fun agregarAlerta(context: Context, alerta: AlertData) {
        // 1. Sincronizamos la memoria con el disco duro por si el servicio
        // está corriendo en segundo plano sin la interfaz abierta.
        if (_alertas.value.isEmpty()) {
            inicializar(context)
        }

        _alertas.update { actual ->
            // Filtramos para que no se duplique la misma señal al mismo tiempo
            if (actual.isNotEmpty() && actual[0].symbol == alerta.symbol && actual[0].senal == alerta.senal) {
                return@update actual
            }

            val nuevaLista = actual.toMutableList()
            nuevaLista.add(0, alerta)

            // Límite estricto de 5 tarjetas en el historial
            val tope = if (nuevaLista.size > 5) nuevaLista.take(5) else nuevaLista

            // Guardado físico en el teléfono, ahora respetando las alertas preexistentes
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("lista", gson.toJson(tope))
            }

            tope
        }

        // Sonido de alerta
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri).play()
        } catch (_: Exception) {}
    }

    // Corregido: Ahora solo recibe AlertData
    fun removerAlerta(context: Context, alerta: AlertData) {
        _alertas.update { actual ->
            val nueva = actual.filter { it != alerta }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("lista", gson.toJson(nueva))
            }
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