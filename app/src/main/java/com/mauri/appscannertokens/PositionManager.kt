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

    fun iniciarMonitoreo(config: UserConfig, symbol: String, side: String, entryPrice: Double, initialTp: Double, apalancamiento: Int) {
        // Evitar duplicados
        if (_positions.value.any { it.symbol == symbol && !it.isClosed }) return

        val nuevaPosicion = ActivePosition(symbol, side, entryPrice, entryPrice, initialTp, initialTp)
        _positions.update { it + nuevaPosicion }

        // Bucle de Trailing Stop Seguro (Estilo Python)
        scope.launch {
            var tpDinamico = initialTp
            var nivelTrailing = 0

            AlertManager.agregarLog("⏱️ Iniciando Trailing Expansivo para $symbol...")

            while (true) {
                delay(1500) // 1.5s para no saturar la API (Anti-Baneo)
                try {
                    val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                    if (posAmt == 0.0) {
                        marcarComoCerrada(symbol)
                        AlertManager.agregarLog("✅ [FIN] Posición $symbol cerrada.")
                        break
                    }

                    val precioActual = BinanceApiManager.obtenerPrecioActual(symbol)

                    // Actualizar UI
                    _positions.update { lista ->
                        lista.map { pos ->
                            if (pos.symbol == symbol) {
                                val dist = if (side == "LONG") precioActual - pos.entryPrice else pos.entryPrice - precioActual
                                val margenEstimado = (pos.entryPrice * abs(posAmt)) / apalancamiento
                                val pnl = dist * abs(posAmt)
                                val roe = if (margenEstimado > 0) (pnl / margenEstimado) * 100 else 0.0
                                pos.copy(currentPrice = precioActual, pnlNeto = pnl, roePct = roe)
                            } else pos
                        }
                    }

                    // --- LÓGICA DEL 70% (EXPANSIVO CON REFLEJO NINJA) ---
                    val distanciaTotal = abs(tpDinamico - entryPrice)
                    if (distanciaTotal > 0) {
                        val progreso = if (side == "LONG") {
                            (precioActual - entryPrice) / distanciaTotal
                        } else {
                            (entryPrice - precioActual) / distanciaTotal
                        }

                        if (progreso >= 0.70) {
                            AlertManager.agregarLog("🔥 [TRAILING] $symbol cruzó el 70%. Modificando escudos...")

                            // 1. Limpieza estricta
                            BinanceApiManager.cancelarOrdenes(config.apiKey, config.apiSecret, symbol)
                            delay(800) // Dar tiempo a Binance

                            val checkPos = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                            if (checkPos == 0.0) {
                                marcarComoCerrada(symbol)
                                AlertManager.agregarLog("✅ [FIN] $symbol tocó el TP explosivamente.")
                                break
                            }

                            // 2. Cálculos matemáticos
                            val distSl = distanciaTotal * 0.60
                            val aumentoTp = distanciaTotal * 0.30

                            val nuevoSl = if (side == "LONG") entryPrice + distSl else entryPrice - distSl
                            val nuevoTp = if (side == "LONG") tpDinamico + aumentoTp else tpDinamico - aumentoTp
                            val ladoSalida = if (side == "LONG") "SELL" else "BUY"

                            // 3. REFLEJO NINJA: Verificar si el precio ya rompió los nuevos límites mientras cancelábamos
                            val precioSeguro = BinanceApiManager.obtenerPrecioActual(symbol)
                            var mercadoCruzado = false

                            if (side == "LONG" && (precioSeguro <= nuevoSl || precioSeguro >= nuevoTp)) mercadoCruzado = true
                            if (side == "SHORT" && (precioSeguro >= nuevoSl || precioSeguro <= nuevoTp)) mercadoCruzado = true

                            if (mercadoCruzado) {
                                AlertManager.agregarLog("⚡ [REFLEJO NINJA] Volatilidad extrema. Cerrando a MARKET para asegurar ganancia.")
                                BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(checkPos), 0.0, config.tipoMargen)
                                marcarComoCerrada(symbol)
                                break
                            }

                            // 4. Colocar nuevos muros
                            val tpOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", nuevoTp)
                            val slOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", nuevoSl)

                            // 5. KILL SWITCH: Si fallan los escudos, huimos.
                            if (!tpOk || !slOk) {
                                AlertManager.agregarLog("🚨 [KILL SWITCH] Binance rechazó los nuevos límites. Rescatando a MARKET...")
                                BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(checkPos), 0.0, config.tipoMargen)
                                marcarComoCerrada(symbol)
                                break
                            }

                            tpDinamico = nuevoTp
                            nivelTrailing++

                            _positions.update { lista ->
                                lista.map { if (it.symbol == symbol) it.copy(dynamicTp = tpDinamico, trailingLevel = nivelTrailing) else it }
                            }
                            AlertManager.agregarLog("🛡️ [ÉXITO NIVEL $nivelTrailing] SL subido | TP extendido.")
                        }
                    }
                } catch (e: Exception) {
                    val errorStr = e.message ?: ""
                    if (errorStr.contains("2021")) {
                        AlertManager.agregarLog("⚠️ [AVISO] Retroceso brusco detectado. Cerrando a MARKET...")
                        // Lanzamos orden a market por precaución
                        val ladoSalida = if (side == "LONG") "SELL" else "BUY"
                        val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                        if (posAmt != 0.0) {
                            BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", abs(posAmt), 0.0, config.tipoMargen)
                        }
                        marcarComoCerrada(symbol)
                        break
                    } else {
                        AlertManager.agregarLog("⚠️ Error en bucle de $symbol: $errorStr")
                    }
                }
            }
        }
    } // <--- Esta es la llave que te faltaba

    private fun marcarComoCerrada(symbol: String) {
        _positions.update { lista ->
            lista.map { if (it.symbol == symbol) it.copy(isClosed = true) else it }
        }
    }

    fun removerPosicion(symbol: String) {
        _positions.update { it.filter { pos -> pos.symbol != symbol } }
    }
}