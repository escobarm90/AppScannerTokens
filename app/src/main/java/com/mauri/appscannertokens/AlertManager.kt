package com.mauri.appscannertokens

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
    // 1. Almacena las alertas en tiempo real (Tarjetas)
    private val _alertas = MutableStateFlow<List<AlertData>>(emptyList())
    val alertas: StateFlow<List<AlertData>> = _alertas.asStateFlow()

    // 2. Almacena los textos de la consola CMD
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun agregarAlerta(alerta: AlertData) {
        _alertas.update { actual ->
            val nuevaLista = actual.toMutableList()
            nuevaLista.add(0, alerta)
            if (nuevaLista.size > 20) nuevaLista.take(20) else nuevaLista
        }
    }

    fun removerAlerta(alerta: AlertData) {
        _alertas.update { actual -> actual.filter { it != alerta } }
    }

    fun agregarLog(mensaje: String) {
        _logs.update { actual ->
            val nuevaLista = actual.toMutableList()
            // Agregamos arriba para que Compose lo dibuje desde abajo hacia arriba (Efecto Terminal)
            nuevaLista.add(0, mensaje)
            // Mantenemos solo los últimos 100 mensajes para no consumir RAM
            if (nuevaLista.size > 100) nuevaLista.take(100) else nuevaLista
        }
    }
}