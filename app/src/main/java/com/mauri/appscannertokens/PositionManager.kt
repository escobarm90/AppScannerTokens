package com.mauri.appscannertokens

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class ActivePosition(
    val symbol: String,
    val side: String,
    val entryPrice: Double,
    var currentPrice: Double,
    var initialTp: Double,
    var dynamicTp: Double,
    var currentSl: Double, // Guardamos el SL dinámico
    var trailingLevel: Int = 0,
    var pnlNeto: Double = 0.0,
    var roePct: Double = 0.0,
    var isClosed: Boolean = false,
    val apalancamiento: Int,
    val orderId: Long
)

object PositionManager {
    private val _positions = MutableStateFlow<List<ActivePosition>>(emptyList())
    val positions: StateFlow<List<ActivePosition>> = _positions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private const val PREFS_NAME = "PositionsPrefs"
    private const val KEY_POSITIONS = "active_positions"

    // ==========================================
    // MEMORIA PERSISTENTE (PUNTO 1)
    // ==========================================
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

                        if (orderEstado in listOf("CANCELED", "REJECTED", "EXPIRED") && posAmt == 0.0) {
                            AlertManager.agregarLog("🗑️ Limpiando ${pos.symbol}: Cancelada y sin posición.")
                            removerPosicion(context, pos.symbol)
                        } else if (orderEstado == "FILLED" && posAmt == 0.0) {
                            AlertManager.agregarLog("🗑️ Limpiando ${pos.symbol}: Posición ya cerrada en Binance.")
                            removerPosicion(context, pos.symbol)
                        } else {
                            AlertManager.agregarLog("✅ Reanudando vigilancia activa para ${pos.symbol}...")
                            vigilarOrden(context, config, pos, true)
                        }
                    }
                }
            }
        }
    }

    private fun guardarEstado(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_POSITIONS, gson.toJson(_positions.value)).apply()
    }

    fun iniciarMonitoreo(context: Context, config: UserConfig, symbol: String, senal: String, entryPrice: Double, tpAbsoluto: Double, slAbsoluto: Double, apalancamiento: Int, orderId: Long) {
        if (_positions.value.any { it.symbol == symbol && !it.isClosed }) return

        val nuevaPosicion = ActivePosition(symbol, senal, entryPrice, entryPrice, tpAbsoluto, tpAbsoluto, slAbsoluto, 0, 0.0, 0.0, false, apalancamiento, orderId)
        _positions.update { it + nuevaPosicion }
        guardarEstado(context) // Blindamos en disco de inmediato

        scope.launch { vigilarOrden(context, config, nuevaPosicion, false) }
    }

    private suspend fun vigilarOrden(context: Context, config: UserConfig, posInicial: ActivePosition, esReanudacion: Boolean) {
        val symbol = posInicial.symbol
        val senal = posInicial.side
        val apalancamiento = posInicial.apalancamiento
        val orderId = posInicial.orderId
        val ladoSalida = if (senal == "LONG") "SELL" else "BUY"

        var tpDinamico = posInicial.dynamicTp
        var slDinamico = posInicial.currentSl
        var nivelTrailing = posInicial.trailingLevel
        val entryPrice = posInicial.entryPrice

        if (!esReanudacion) {
            AlertManager.agregarLog("👀 Vigilando orden $symbol en el Order Book...")

            var isFilled = false
            for (i in 1..9000) {
                val estado = BinanceApiManager.obtenerEstadoOrden(config.apiKey, config.apiSecret, symbol, orderId)
                if (estado == "FILLED") {
                    isFilled = true
                    break
                } else if (estado in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                    AlertManager.agregarLog("❌ [AVISO] Orden $symbol cancelada.")
                    removerPosicion(context, symbol)
                    return
                }
                delay(1000)
            }

            if (!isFilled) return
            AlertManager.agregarLog("✅ ¡Orden $symbol EJECUTADA! Colocando escudos físicos...")

            BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)

            val precioActualSeguro = BinanceApiManager.obtenerPrecioActual(symbol)

            // 1. RESCATAMOS LAS DISTANCIAS ORIGINALES
            val distSlOriginal = kotlin.math.abs(posInicial.entryPrice - posInicial.currentSl)
            val distTpOriginal = kotlin.math.abs(posInicial.dynamicTp - posInicial.entryPrice)

            // 2. RECALCULAMOS BASADO EN EL PRECIO REAL DE EJECUCIÓN
            var slFinal = if (senal == "LONG") precioActualSeguro - distSlOriginal else precioActualSeguro + distSlOriginal
            var tpFinal = if (senal == "LONG") precioActualSeguro + distTpOriginal else precioActualSeguro - distTpOriginal

            // 3. BLINDAJE ANTI-RECHAZO DE BINANCE (Mínimo 0.4%)
            val distMinimaVital = precioActualSeguro * 0.004
            if (kotlin.math.abs(precioActualSeguro - slFinal) < distMinimaVital) {
                slFinal = if (senal == "LONG") precioActualSeguro - distMinimaVital else precioActualSeguro + distMinimaVital
            }
            if (kotlin.math.abs(tpFinal - precioActualSeguro) < distMinimaVital) {
                tpFinal = if (senal == "LONG") precioActualSeguro + distMinimaVital else precioActualSeguro - distMinimaVital
            }

            AlertManager.agregarLog(
                "🛡️ INTENTO DE COLOCAR ESCUDOS FÍSICOS:\n" +
                        "Símbolo: $symbol\n" +
                        "Lado Salida: $ladoSalida\n" +
                        "SL Calculado: $slFinal\n" +
                        "TP Calculado: $tpFinal"
            )

            val tpOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", tpFinal)
            val slOk = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", slFinal)

            // ✅ SOLUCIÓN DEFINITIVA: APAGAMOS EL KILL SWITCH DE RESCATE
            if (!tpOk || !slOk) {
                AlertManager.agregarLog(
                    "⚠️ RECHAZO DE BINANCE DETECTADO:\n" +
                            "Motivo: Slippage extremo invalidó la orden física.\n" +
                            "Acción: Posición MANTENIDA ABIERTA.\n" +
                            "Protección: Activando Escudo VIRTUAL en RAM."
                )
            } else {
                AlertManager.agregarLog("✅ Escudos físicos aceptados en Binance.")
            }

            tpDinamico = tpFinal
            slDinamico = slFinal
            _positions.update { l -> l.map { if (it.symbol == symbol) it.copy(dynamicTp = tpFinal, currentSl = slFinal) else it } }
            guardarEstado(context)
        }

        // ==========================================
        // FASE 3: TRAILING STOP EXPANSIVO (0.3s)
        // ==========================================
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

                val precioActual = BinanceApiManager.preciosWs[symbol]
                if (precioActual == null) continue

                // 3. ACTUALIZAR UI EN VIVO
                _positions.update { lista ->
                    lista.map { pos ->
                        if (pos.symbol == symbol) {
                            val dist = if (senal == "LONG") precioActual - pos.entryPrice else pos.entryPrice - precioActual
                            val margenEstimado = (pos.entryPrice * kotlin.math.abs(ultimoMontoPosicion)) / apalancamiento
                            val pnl = dist * kotlin.math.abs(ultimoMontoPosicion)
                            val roe = if (margenEstimado > 0) (pnl / margenEstimado) * 100 else 0.0
                            pos.copy(currentPrice = precioActual, pnlNeto = pnl, roePct = roe)
                        } else pos
                    }
                }

                // ✅ ESCUDO VIRTUAL EN RAM (Te protege si Binance rechazó el físico)
                if (ultimoMontoPosicion > 0.0) {
                    val tocoSlVirtual = if (senal == "LONG") precioActual <= slDinamico else precioActual >= slDinamico
                    val tocoTpVirtual = if (senal == "LONG") precioActual >= tpDinamico else precioActual <= tpDinamico

                    if (tocoSlVirtual || tocoTpVirtual) {
                        AlertManager.agregarLog(
                            "⚡ EJECUCIÓN VIRTUAL EN RAM:\n" +
                                    "Símbolo: $symbol\n" +
                                    "Motivo: Precio cruzó SL/TP Dinámico.\n" +
                                    "Acción: Ejecutando cierre a MARKET de emergencia."
                        )
                        BinanceApiManager.limpiarOrdenesEstrictamente(config.apiKey, config.apiSecret, symbol)
                        BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", kotlin.math.abs(ultimoMontoPosicion), 0.0, config.tipoMargen)
                        marcarComoCerrada(context, symbol)
                        break
                    }
                }

                // 4. LÓGICA DEL 70% (REACCIÓN INMEDIATA)
                val distanciaTotal = kotlin.math.abs(tpDinamico - entryPrice)
                if (distanciaTotal > 0) {
                    val progreso = if (senal == "LONG") (precioActual - entryPrice) / distanciaTotal else (entryPrice - precioActual) / distanciaTotal

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

                        val distSl = distanciaTotal * 0.60
                        val aumentoTp = distanciaTotal * 0.30

                        val nuevoSl = if (senal == "LONG") entryPrice + distSl else entryPrice - distSl
                        val nuevoTp = if (senal == "LONG") tpDinamico + aumentoTp else tpDinamico - aumentoTp

                        val precioSeguro = BinanceApiManager.preciosWs[symbol] ?: BinanceApiManager.obtenerPrecioActual(symbol)
                        var mercadoCruzado = false
                        if (senal == "LONG" && (precioSeguro <= nuevoSl || precioSeguro >= nuevoTp)) mercadoCruzado = true
                        if (senal == "SHORT" && (precioSeguro >= nuevoSl || precioSeguro <= nuevoTp)) mercadoCruzado = true

                        if (mercadoCruzado) {
                            AlertManager.agregarLog("⚡ Volatilidad extrema. Cerrando a MARKET.")
                            BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, symbol, ladoSalida, "MARKET", kotlin.math.abs(checkPos), 0.0, config.tipoMargen)
                            marcarComoCerrada(context, symbol)
                            break
                        }

                        val tpOk2 = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "TAKE_PROFIT_MARKET", nuevoTp)
                        val slOk2 = BinanceApiManager.crearOrdenStop(config.apiKey, config.apiSecret, symbol, ladoSalida, "STOP_MARKET", nuevoSl)

                        if (!tpOk2 || !slOk2) {
                            AlertManager.agregarLog("🚨 Binance rechazó expansivos. La protección Virtual en RAM sigue activa en los nuevos valores.")
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
                        AlertManager.agregarLog("🛡️ [ÉXITO NIVEL $nivelTrailing] SL subido | TP extendido.")
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

    private fun marcarComoCerrada(context: Context, symbol: String) {
        _positions.update { lista -> lista.map { if (it.symbol == symbol) it.copy(isClosed = true) else it } }
        guardarEstado(context)
    }

    fun removerPosicion(context: Context, symbol: String) {
        _positions.update { it.filter { pos -> pos.symbol != symbol } }
        guardarEstado(context)
    }
}