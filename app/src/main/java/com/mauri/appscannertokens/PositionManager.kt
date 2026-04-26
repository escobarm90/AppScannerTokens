package com.mauri.appscannertokens

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActivePosition(
    val symbol: String,
    val side: String,
    val entryPrice: Double,
    var currentPrice: Double,
    var initialTp: Double,
    var dynamicTp: Double,
    var currentSl: Double,
    var trailingLevel: Int = 0,
    var pnlNeto: Double = 0.0,
    var roePct: Double = 0.0,
    var isClosed: Boolean = false,
    val apalancamiento: Int,
    val orderId: Long,
    val quantity: Double = 0.0
)

object PositionManager {
    private val _positions = MutableStateFlow<List<ActivePosition>>(emptyList())
    val positions: StateFlow<List<ActivePosition>> = _positions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private const val PREFS_NAME = "PositionsPrefs"
    private const val KEY_POSITIONS = "active_positions"

    fun inicializar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_POSITIONS, null)
        if (json != null) {
            val type = object : TypeToken<List<ActivePosition>>() {}.type
            val guardadas: List<ActivePosition> = gson.fromJson(json, type)

            val activas = guardadas.filter { !it.isClosed }
            _positions.value = activas

            if (activas.isNotEmpty()) {
                AlertManager.agregarLog("🔄 [SISTEMA] Verificando estado de ${activas.size} órdenes...")
                val config = cargarConfiguracion(context)

                activas.forEach { pos ->
                    scope.launch {
                        val orderEstado = BinanceApiManager.obtenerEstadoOrden(config.apiKey, config.apiSecret, pos.symbol, pos.orderId)
                        val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, pos.symbol) ?: 0.0

                        when (orderEstado) {
                            in listOf("CANCELED", "REJECTED", "EXPIRED") -> {
                                if (posAmt == 0.0) {
                                    AlertManager.agregarLog("🗑️ Limpiando ${pos.symbol}: Cancelada y sin posición.")
                                    removerPosicion(context, pos.symbol)
                                } else {
                                    AlertManager.agregarLog("✅ Reanudando vigilancia activa para ${pos.symbol}...")
                                    vigilarOrden(context, config, pos, esReanudacion = true)
                                }
                            }
                            "FILLED" -> {
                                if (posAmt == 0.0) {
                                    AlertManager.agregarLog("🗑️ Limpiando ${pos.symbol}: Posición ya cerrada en Binance.")
                                    removerPosicion(context, pos.symbol)
                                } else {
                                    AlertManager.agregarLog("✅ Reanudando vigilancia activa para ${pos.symbol}...")
                                    vigilarOrden(context, config, pos, esReanudacion = true)
                                }
                            }
                            else -> {
                                AlertManager.agregarLog("✅ Reanudando vigilancia activa para ${pos.symbol}...")
                                vigilarOrden(context, config, pos, esReanudacion = true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cargarConfiguracion(context: Context): UserConfig {
        val prefs = context.getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
        val jsonGuardado = prefs.getString("config_data", null)
        return if (jsonGuardado != null) gson.fromJson(jsonGuardado, UserConfig::class.java) else UserConfig()
    }

    private fun guardarEstado(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(_positions.value)
        prefs.edit().putString(KEY_POSITIONS, json).apply()
    }

    fun iniciarMonitoreo(context: Context, config: UserConfig, symbol: String, senal: String, entryPrice: Double, tpAbsoluto: Double, slAbsoluto: Double, apalancamiento: Int, orderId: Long, quantity: Double) {
        if (_positions.value.any { it.symbol == symbol && !it.isClosed }) return

        val nuevaPosicion = ActivePosition(symbol, senal, entryPrice, entryPrice, tpAbsoluto, tpAbsoluto, slAbsoluto, 0, 0.0, 0.0, false, apalancamiento, orderId, quantity)
        _positions.update { it + nuevaPosicion }
        guardarEstado(context)
        AlertManager.agregarLog("📡 [VIGILANCIA] Posición añadida al monitor: ${nuevaPosicion.symbol}")

        scope.launch { vigilarOrden(context, config, nuevaPosicion, false) }
    }

    private suspend fun vigilarOrden(context: Context, config: UserConfig, posInicial: ActivePosition, esReanudacion: Boolean) {
        val symbol = posInicial.symbol
        val senal = posInicial.side.uppercase()
        val apalancamiento = posInicial.apalancamiento
        val orderId = posInicial.orderId

        val isLong = senal == "LONG" || senal == "BUY"
        val ladoSalida = if (isLong) "SELL" else "BUY"

        var tpDinamico = posInicial.dynamicTp
        var slDinamico = posInicial.currentSl
        var nivelTrailing = posInicial.trailingLevel
        val entryPrice = posInicial.entryPrice

        if (!esReanudacion) {
            AlertManager.agregarLog("🛡️ Iniciando protección inmediata para $symbol...")

            // Limpiamos órdenes previas ANTES de colocar los nuevos escudos
            BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)

            var slFinal = posInicial.currentSl
            var tpFinal = posInicial.dynamicTp

            // REGLA DE SUPERVIVENCIA ESTRICTA (Calculada sobre el precio de entrada deseado)
            val distMinimaVital = entryPrice * 0.004
            if (isLong) {
                if (slFinal > entryPrice - distMinimaVital) slFinal = entryPrice - distMinimaVital
                if (tpFinal < entryPrice + distMinimaVital) tpFinal = entryPrice + distMinimaVital
            } else {
                if (slFinal < entryPrice + distMinimaVital) slFinal = entryPrice + distMinimaVital
                if (tpFinal > entryPrice - distMinimaVital) tpFinal = entryPrice - distMinimaVital
            }

            AlertManager.agregarLog(
                "🛡️ COLOCANDO ESCUDOS (PREVENTIVOS):\n" +
                        "Símbolo: $symbol | Lado: $ladoSalida\n" +
                        "SL: $slFinal | TP: $tpFinal"
            )

            // Colocamos las órdenes de TP y SL inmediatamente, incluso antes de que la orden principal se llene.
            // Binance permite órdenes Close-Position (Reduce Only) preventivas.
            val tpOk = BinanceApiManager.crearEscudoGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", tpFinal, posInicial.quantity, config.tipoMargen)
            val slOk = BinanceApiManager.crearEscudoGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", slFinal, posInicial.quantity, config.tipoMargen)

            if (!tpOk || !slOk) {
                AlertManager.agregarLog("⚠️ Algunos escudos físicos fallaron tras reintentos. El Escudo VIRTUAL en RAM los respaldará.")
            } else {
                AlertManager.agregarLog("✅ Escudos físicos asegurados en Binance.")
            }

            // Actualizamos el estado con los valores finales ajustados por la regla vital
            tpDinamico = tpFinal
            slDinamico = slFinal
            _positions.update { l -> l.map { if (it.symbol == symbol) it.copy(dynamicTp = tpFinal, currentSl = slFinal) else it } }
            guardarEstado(context)

            AlertManager.agregarLog("👀 Esperando ejecución de $symbol en el Order Book...")

            var isFilled = false
            repeat(9000) {
                val estado = BinanceApiManager.obtenerEstadoOrden(config.apiKey, config.apiSecret, symbol, orderId)
                if (estado == "FILLED") {
                    isFilled = true
                    return@repeat
                } else if (estado in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                    AlertManager.agregarLog("❌ [AVISO] Orden $symbol cancelada o expirada.")
                    BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)
                    removerPosicion(context, symbol)
                    return
                }
                delay(1000)
            }

            if (!isFilled) return
            AlertManager.agregarLog("✅ ¡Orden $symbol EJECUTADA! Vigilancia activa iniciada.")
        }

        AlertManager.agregarLog("⏱️ Iniciando Trailing 0.3s (RAM WS) para $symbol...")
        var ultimoChequeoApi = 0L
        var ultimoMontoPosicion = 0.0

        while (true) {
            delay(300)
            try {
                if (System.currentTimeMillis() - ultimoChequeoApi > 2500) {
                    val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                    if (posAmt != null) {
                        ultimoMontoPosicion = posAmt
                        ultimoChequeoApi = System.currentTimeMillis()
                        if (posAmt == 0.0) {
                            marcarComoCerrada(context, symbol)
                            AlertManager.agregarLog("✅ [FIN] Posición $symbol cerrada naturalmente.")
                            break
                        }
                    }
                }

                val precioActual = BinanceApiManager.preciosWs[symbol] ?: continue

                _positions.update { lista ->
                    lista.map { pos ->
                        if (pos.symbol == symbol) {
                            // --- CÁLCULO PNL CORREGIDO ---
                            val dist = if (isLong) precioActual - pos.entryPrice else pos.entryPrice - precioActual
                            val margenEstimado = (pos.entryPrice * kotlin.math.abs(ultimoMontoPosicion)) / apalancamiento
                            val pnl = dist * kotlin.math.abs(ultimoMontoPosicion)
                            val roe = if (margenEstimado > 0) (pnl / margenEstimado) * 100 else 0.0
                            
                            // IMPORTANTE: NO usamos copy() aquí porque copy() crea un objeto nuevo que el StateFlow detecta bien.
                            pos.copy(currentPrice = precioActual, pnlNeto = pnl, roePct = roe)
                        } else pos
                    }
                }

                if (ultimoMontoPosicion != 0.0) {
                    val tocoSlVirtual = if (isLong) precioActual <= slDinamico else precioActual >= slDinamico
                    val tocoTpVirtual = if (isLong) precioActual >= tpDinamico else precioActual <= tpDinamico

                    if (tocoSlVirtual || tocoTpVirtual) {
                        AlertManager.agregarLog(
                            "⚡ EJECUCIÓN VIRTUAL EN RAM:\n" +
                                    "Símbolo: $symbol\n" +
                                    "Motivo: Precio cruzó SL/TP Dinámico.\n" +
                                    "Acción: Ejecutando cierre a MARKET de emergencia (Garantizado)."
                        )
                        BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)
                        BinanceApiManager.ejecutarCierreGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, kotlin.math.abs(ultimoMontoPosicion), config.tipoMargen)
                        marcarComoCerrada(context, symbol)
                        break
                    }
                }

                val distanciaTotal = kotlin.math.abs(tpDinamico - entryPrice)
                if (distanciaTotal > 0) {
                    val progreso = if (isLong) (precioActual - entryPrice) / distanciaTotal else (entryPrice - precioActual) / distanciaTotal

                    if (progreso >= 0.70) {
                        AlertManager.agregarLog("🔥 [TRAILING] $symbol cruzó el 70%. Modificando escudos...")

                        BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)

                        val checkPos = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol)
                        if (checkPos == null) continue
                        if (checkPos == 0.0) {
                            marcarComoCerrada(context, symbol)
                            AlertManager.agregarLog("✅ [FIN] $symbol tocó el TP explosivamente.")
                            break
                        }

                        val distSl = distanciaTotal * 0.50
                        val aumentoTp = distanciaTotal * 0.30

                        val nuevoSl = if (isLong) entryPrice + distSl else entryPrice - distSl
                        val nuevoTp = if (isLong) tpDinamico + aumentoTp else tpDinamico - aumentoTp

                        val precioSeguro = BinanceApiManager.preciosWs[symbol] ?: BinanceApiManager.obtenerPrecioActual(symbol)
                        var mercadoCruzado = false
                        if (isLong && (precioSeguro <= nuevoSl || precioSeguro >= nuevoTp)) mercadoCruzado = true
                        if (!isLong && (precioSeguro >= nuevoSl || precioSeguro <= nuevoTp)) mercadoCruzado = true

                        if (mercadoCruzado) {
                            AlertManager.agregarLog("⚡ Volatilidad extrema. Cerrando a MARKET (Garantizado).")
                            BinanceApiManager.ejecutarCierreGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, kotlin.math.abs(checkPos), config.tipoMargen)
                            marcarComoCerrada(context, symbol)
                            break
                        }

                        val tpOk2 = BinanceApiManager.crearEscudoGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", nuevoTp, kotlin.math.abs(checkPos), config.tipoMargen)
                        val slOk2 = BinanceApiManager.crearEscudoGarantizado(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", nuevoSl, kotlin.math.abs(checkPos), config.tipoMargen)

                        if (!tpOk2 || !slOk2) {
                            AlertManager.agregarLog("🚨 Binance rechazó expansivos tras reintentos. La protección Virtual en RAM sigue activa.")
                        }

                        tpDinamico = nuevoTp
                        slDinamico = nuevoSl
                        nivelTrailing++

                        _positions.update { lista ->
                            lista.map {
                                if (it.symbol == symbol) it.copy(dynamicTp = tpDinamico, currentSl = slDinamico, trailingLevel = nivelTrailing)
                                else it
                            }
                        }
                        guardarEstado(context)
                        AlertManager.agregarLog("🛡️ [ÉXITO NIVEL $nivelTrailing] SL asegurado al 50% | TP extendido 30%.")
                    }
                }
            } catch (e: Exception) {
                val errorStr = e.message ?: ""
                if (errorStr.contains("2021") || errorStr.contains("4509")) {
                    AlertManager.agregarLog("⚠️ [AVISO] Mercado retrocedió o cerró por TP.")
                    marcarComoCerrada(context, symbol)
                    break
                }
            }
        }
    }

    fun cerrarPosicionManual(context: Context, config: UserConfig, symbol: String) {
        scope.launch {
            try {
                AlertManager.agregarLog("⚠️ CIERRE MANUAL INICIADO PARA $symbol")
                BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)

                val posAmt = BinanceApiManager.obtenerCantidadPosicion(config.apiKey, config.apiSecret, symbol) ?: 0.0
                if (posAmt != 0.0) {
                    val ladoReal = if (posAmt > 0) "SELL" else "BUY"
                    val qtyStr = kotlin.math.abs(posAmt)

                    BinanceApiManager.ejecutarCierreGarantizado(
                        apiKey = config.apiKey,
                        apiSecret = config.apiSecret,
                        symbol = symbol,
                        side = ladoReal,
                        quantity = qtyStr,
                        marginType = config.tipoMargen
                    )
                    AlertManager.agregarLog("✅ $symbol CERRADA MANUALMENTE (Cierre Garantizado).")
                } else {
                    AlertManager.agregarLog("⚠️ $symbol ya no contaba con cantidad activa en Binance.")
                }
                marcarComoCerrada(context, symbol)
            } catch (e: Exception) {
                AlertManager.agregarLog("❌ Error en cierre manual de $symbol: ${e.message}")
            }
        }
    }

    private fun marcarComoCerrada(context: Context, symbol: String) {
        _positions.update { lista -> lista.map { if (it.symbol == symbol) it.copy(isClosed = true) else it } }
        guardarEstado(context)
    }

    fun removerPosicion(context: Context, symbol: String) {
        _positions.update { it.filter { pos -> pos.symbol != symbol } }
        guardarEstado(context)
    }
}
