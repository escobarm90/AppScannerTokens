package com.mauri.appscannertokens

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mauri.appscannertokens.AlertManager.agregarLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    val orderId: Long
)

object PositionManager {

    private val _positions = MutableStateFlow<List<ActivePosition>>(emptyList())
    val positions: StateFlow<List<ActivePosition>> = _positions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private const val PREFS_NAME = "PositionsPrefs"
    private const val KEY_POSITIONS = "active_positions"

    fun iniciarMonitoreo(
        context: Context,
        config: UserConfig,
        symbol: String,
        senal: String,
        entryPrice: Double,
        tp: Double,
        sl: Double,
        apalancamiento: Int,
        orderId: Long
    ) {
        if (_positions.value.any { it.symbol == symbol && !it.isClosed }) return

        val pos = ActivePosition(
            symbol, senal, entryPrice, entryPrice,
            tp, tp, sl, 0, 0.0, 0.0, false,
            apalancamiento, orderId
        )

        _positions.update { it + pos }
        guardarEstado(context)

        scope.launch {
            vigilarOrden(context, config, pos, false)
        }
    }

    private suspend fun vigilarOrden(
        context: Context,
        config: UserConfig,
        pos: ActivePosition,
        esReanudacion: Boolean
    ) {

        val symbol = pos.symbol
        val isLong = pos.side.uppercase() in listOf("LONG", "BUY")
        val ladoSalida = if (isLong) "SELL" else "BUY"

        var tp = pos.dynamicTp
        var sl = pos.currentSl

        val entry = pos.entryPrice

        // ---------- ESPERAR FILLED ----------
        if (!esReanudacion) {
            for (i in 1..60) {
                val estado = BinanceApiManager.obtenerEstadoOrden(
                    config.apiKey, config.apiSecret, symbol, pos.orderId
                )

                if (estado == "FILLED") break

                if (estado in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                    removerPosicion(context, symbol)
                    return
                }

                delay(1000)
            }

            // ---------- CREAR ESCUDOS ----------
            val tpOk = BinanceApiManager.crearEscudoGarantizado(
                config.apiKey,
                config.apiSecret,
                symbol,
                ladoSalida,
                "TAKE_PROFIT_MARKET",
                tp
            )

            val slOk = BinanceApiManager.crearEscudoGarantizado(
                config.apiKey,
                config.apiSecret,
                symbol,
                ladoSalida,
                "STOP_MARKET",
                sl
            )

            if (!tpOk || !slOk) {
                agregarLog("⚠️ Escudos físicos rechazados → se activa virtual")
            } else {
                agregarLog("✅ SL/TP colocados en Binance")
            }
        }

        // ---------- LOOP TRAILING ----------
        while (true) {
            delay(300)

            val posAmt = BinanceApiManager.obtenerCantidadPosicion(
                config.apiKey, config.apiSecret, symbol
            ) ?: 0.0

            if (posAmt == 0.0) {
                marcarComoCerrada(context, symbol)
                break
            }

            val precio = BinanceApiManager.preciosWs[symbol]
                ?: BinanceApiManager.obtenerPrecioActual(symbol)

            if (precio == 0.0) continue

            // ---------- ESCUDO VIRTUAL ----------
            val hitSL = if (isLong) precio <= sl else precio >= sl
            val hitTP = if (isLong) precio >= tp else precio <= tp

            if (hitSL || hitTP) {
                BinanceApiManager.limpiarOrdenesEstrictamente(
                    config.apiKey, config.apiSecret, symbol
                )

                BinanceApiManager.ejecutarCierreGarantizado(
                    config.apiKey, config.apiSecret, symbol,
                    ladoSalida, kotlin.math.abs(posAmt), config.tipoMargen
                )

                marcarComoCerrada(context, symbol)
                break
            }

            // ---------- TRAILING 70% ----------
            val distancia = kotlin.math.abs(tp - entry)
            val progreso = if (isLong)
                (precio - entry) / distancia
            else
                (entry - precio) / distancia

            if (progreso >= 0.70) {

                BinanceApiManager.limpiarOrdenesEstrictamente(
                    config.apiKey, config.apiSecret, symbol
                )

                val nuevoSL = if (isLong)
                    entry + (distancia * 0.5)
                else
                    entry - (distancia * 0.5)

                val nuevoTP = if (isLong)
                    tp + (distancia * 0.3)
                else
                    tp - (distancia * 0.3)

                BinanceApiManager.crearEscudoGarantizado(
                    config.apiKey, config.apiSecret, symbol,
                    ladoSalida, "STOP_MARKET", nuevoSL
                )

                BinanceApiManager.crearEscudoGarantizado(
                    config.apiKey, config.apiSecret, symbol,
                    ladoSalida, "TAKE_PROFIT_MARKET", nuevoTP
                )

                tp = nuevoTP
                sl = nuevoSL

                agregarLog("🔥 Trailing ejecutado en $symbol")
            }
        }
    }

    private fun guardarEstado(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_POSITIONS, gson.toJson(_positions.value)).apply()
    }

    private fun marcarComoCerrada(context: Context, symbol: String) {
        _positions.update { it.map { p -> if (p.symbol == symbol) p.copy(isClosed = true) else p } }
        guardarEstado(context)
    }

    fun removerPosicion(context: Context, symbol: String) {
        _positions.update { it.filter { p -> p.symbol != symbol } }
        guardarEstado(context)
    }
}