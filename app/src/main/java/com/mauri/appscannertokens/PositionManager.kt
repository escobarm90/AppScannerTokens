package com.mauri.appscannertokens

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class ActivePosition(
    val symbol: String,
    val side: String, // "LONG" o "SHORT"
    val entryPrice: Double,
    var currentPrice: Double,
    var initialTp: Double,
    var dynamicTp: Double,
    var trailingLevel: Int = 0,
    var pnlNeto: Double = 0.0,
    var roePct: Double = 0.0,
    var isClosed: Boolean = false
)

object PositionManager {
    private val _positions = MutableStateFlow<List<ActivePosition>>(emptyList())
    val positions: StateFlow<List<ActivePosition>> = _positions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun iniciarMonitoreo(config: UserConfig, symbol: String, senal: String, entryPrice: Double, tpAbsoluto: Double, slAbsoluto: Double, apalancamiento: Int, orderId: Long) {
        if (_positions.value.any { it.symbol == symbol && !it.isClosed }) return

        val nuevaPosicion = ActivePosition(symbol, senal, entryPrice, entryPrice, tpAbsoluto, tpAbsoluto)
        _positions.update { it + nuevaPosicion }

        scope.launch {
            AlertManager.agregarLog("👀 Vigilando orden $symbol en el Order Book...")

            // ==========================================
            // FASE 1: PERRO GUARDIÁN (Esperar a que se llene)
            // ==========================================
            var isFilled = false
            for (i in 1..9000) {
                val estado = BinanceApiManager.obtenerEstadoOrden(config.apiKey, config.apiSecret, symbol, orderId)
                if (estado == "FILLED") {
                    isFilled = true
                    break
                } else if (estado in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                    AlertManager.agregarLog("❌ [AVISO] Orden $symbol cancelada antes de llenarse.")
                    removerPosicion(symbol)
                    return@launch
                }
                delay(1000) // 1 seg (Igual que en Python)
            }

            if (!isFilled) return@launch

            AlertManager.agregarLog("✅ ¡Orden $symbol EJECUTADA! Colocando escudos físicos...")

            // ==========================================
            // FASE 2: COLOCAR ESCUDOS FÍSICOS
            // ==========================================
            val ladoSalida = if (senal == "LONG") "SELL" else "BUY"

            BinanceApiManager.cancelarOrdenes(config.apiKey, config.apiSecret, symbol)
            delay(800)

            val tpOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", tpAbsoluto)
            val slOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", slAbsoluto)

            if (!tpOk || !slOk) {
                AlertManager.agregarLog("🚨 [KILL SWITCH] Fallo al colocar escudos. Abortando posición...")
                val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                if (posAmt != 0.0) BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(posAmt), 0.0, config.tipoMargen)
                marcarComoCerrada(symbol)
                return@launch
            }

            // ==========================================
            // FASE 3: TRAILING STOP EXPANSIVO
            // ==========================================
            var tpDinamico = tpAbsoluto
            var nivelTrailing = 0
            AlertManager.agregarLog("⏱️ Iniciando Trailing Expansivo para $symbol...")

            while (true) {
                delay(2500) // Chequeo API (Golpeamos Binance cada 2.5s como el Watchdog)
                try {
                    val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                    if (posAmt == 0.0) {
                        marcarComoCerrada(symbol)
                        AlertManager.agregarLog("✅ [FIN] Posición $symbol cerrada naturalmente.")
                        break
                    }

                    val precioActual = BinanceApiManager.obtenerPrecioActual(symbol)

                    // Actualizar UI
                    _positions.update { lista ->
                        lista.map { pos ->
                            if (pos.symbol == symbol) {
                                val dist = if (senal == "LONG") precioActual - pos.entryPrice else pos.entryPrice - precioActual
                                val margenEstimado = (pos.entryPrice * abs(posAmt)) / apalancamiento
                                val pnl = dist * abs(posAmt)
                                val roe = if (margenEstimado > 0) (pnl / margenEstimado) * 100 else 0.0
                                pos.copy(currentPrice = precioActual, pnlNeto = pnl, roePct = roe)
                            } else pos
                        }
                    }

                    // --- LÓGICA DEL 70% ---
                    val distanciaTotal = abs(tpDinamico - entryPrice)
                    if (distanciaTotal > 0) {
                        val progreso = if (senal == "LONG") (precioActual - entryPrice) / distanciaTotal else (entryPrice - precioActual) / distanciaTotal

                        if (progreso >= 0.70) {
                            AlertManager.agregarLog("🔥 [TRAILING] $symbol cruzó el 70%. Modificando escudos...")

                            BinanceApiManager.cancelarOrdenes(config.apiKey, config.apiSecret, symbol)
                            delay(800)

                            val checkPos = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                            if (checkPos == 0.0) {
                                marcarComoCerrada(symbol)
                                AlertManager.agregarLog("✅ [FIN] $symbol tocó el TP explosivamente.")
                                break
                            }

                            val distSl = distanciaTotal * 0.60
                            val aumentoTp = distanciaTotal * 0.30

                            val nuevoSl = if (senal == "LONG") entryPrice + distSl else entryPrice - distSl
                            val nuevoTp = if (senal == "LONG") tpDinamico + aumentoTp else tpDinamico - aumentoTp

                            val precioSeguro = BinanceApiManager.obtenerPrecioActual(symbol)
                            var mercadoCruzado = false

                            if (senal == "LONG" && (precioSeguro <= nuevoSl || precioSeguro >= nuevoTp)) mercadoCruzado = true
                            if (senal == "SHORT" && (precioSeguro >= nuevoSl || precioSeguro <= nuevoTp)) mercadoCruzado = true

                            if (mercadoCruzado) {
                                AlertManager.agregarLog("⚡ [REFLEJO NINJA] Volatilidad extrema. Cerrando a MARKET para asegurar ganancia.")
                                BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(checkPos), 0.0, config.tipoMargen)
                                marcarComoCerrada(symbol)
                                break
                            }

                            val tpOk2 = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", nuevoTp)
                            val slOk2 = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", nuevoSl)

                            if (!tpOk2 || !slOk2) {
                                AlertManager.agregarLog("🚨 [KILL SWITCH] Binance rechazó los límites. Rescatando a MARKET...")
                                BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(checkPos), 0.0, config.tipoMargen)
                                marcarComoCerrada(symbol)
                                break
                            }

                            tpDinamico = nuevoTp
                            nivelTrailing++

                            _positions.update { lista -> lista.map { if (it.symbol == symbol) it.copy(dynamicTp = tpDinamico, trailingLevel = nivelTrailing) else it } }
                            AlertManager.agregarLog("🛡️ [ÉXITO NIVEL $nivelTrailing] SL subido | TP extendido.")
                        }
                    }
                } catch (e: Exception) {
                    val errorStr = e.message ?: ""
                    if (errorStr.contains("2021") || errorStr.contains("4509")) {
                        AlertManager.agregarLog("⚠️ [AVISO] Mercado retrocedió o cerró por TP.")
                        marcarComoCerrada(symbol)
                        break
                    } else {
                        AlertManager.agregarLog("⚠️ Error en bucle de $symbol: $errorStr")
                    }
                }
            }
        }
    }

    private fun marcarComoCerrada(symbol: String) {
        _positions.update { lista ->
            lista.map { if (it.symbol == symbol) it.copy(isClosed = true) else it }
        }
    }

    fun removerPosicion(symbol: String) {
        _positions.update { it.filter { pos -> pos.symbol != symbol } }
    }
}