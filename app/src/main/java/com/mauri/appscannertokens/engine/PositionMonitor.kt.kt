package com.mauri.appscannertokens.engine

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import kotlinx.coroutines.*

class PositionMonitor(
    private val config: UserConfig,
    private val initialPosition: ActivePosition,
    private val accountService: BinanceAccountService,
    private val orderService: BinanceOrderService,
    private val marketDataService: BinanceMarketDataService,
    private val tickerWebSocket: BinanceTickerWebSocket,
    private val positionRepository: PositionRepository,
    private val logRepository: LogRepository,
    private val trailingStopEngine: TrailingStopEngine
) {
    suspend fun run(isResume: Boolean) = coroutineScope {
        var position = initialPosition

        logRepository.add("MONITOR INICIADO: ${position.symbol}. Control delegado a Binance.")

        while (isActive && !position.isClosed) {
            val currentPrice = tickerWebSocket.price(position.symbol)

            if (currentPrice != null && currentPrice > 0) {
                // Cálculo de métricas para la UI
                val isLong = position.side == "LONG"
                val pnlNeto = if (isLong) (currentPrice - position.entryPrice) * position.quantity else (position.entryPrice - currentPrice) * position.quantity
                val margin = (position.entryPrice * position.quantity) / position.apalancamiento
                val roePct = if (margin > 0) (pnlNeto / margin) * 100 else 0.0

                // Actualizar estado LOCAL para que la UI se refresque (real-time)
                position = position.copy(currentPrice = currentPrice, pnlNeto = pnlNeto, roePct = roePct)
                positionRepository.update(position.symbol) { position }

                // TRAILING STOP: Mover SL en Binance
                val newSl = trailingStopEngine.calculateNewStopLoss(position, currentPrice)
                if (newSl != null && abs(newSl - position.currentSl) > (position.entryPrice * 0.0001)) { // Tolerancia mínima

                    val closeSide = if (isLong) "SELL" else "BUY"
                    val orders = orderService.getOpenOrders(config.apiKey, config.apiSecret, position.symbol)
                    val slOrder = orders?.firstOrNull { it.type == "STOP_MARKET" && it.closePosition }

                    if (slOrder != null) {
                        orderService.cancelOrder(config.apiKey, config.apiSecret, position.symbol, slOrder.orderId)
                        val slResult = orderService.placeStopLoss(config.apiKey, config.apiSecret, position.symbol, closeSide, newSl)

                        if (slResult.success) {
                            position = position.copy(currentSl = newSl, trailingLevel = trailingStopEngine.getNewLevel(position, currentPrice))
                            positionRepository.update(position.symbol) { position }
                            logRepository.add("SL Actualizado en Binance: $newSl")
                        } else {
                            // AQUÍ ESTÁ EL CAMBIO: Logueamos el error y NO CERRAMOS A MERCADO
                            logRepository.add("ERROR BINANCE: No se pudo actualizar SL: ${slResult.message}")
                        }
                    }
                }
            }

            // Chequeo de seguridad: Si Binance cerró la orden (por TP o SL), marcamos como cerrada
            delay(3000)
            val amount = accountService.getPositionAmount(config.apiKey, config.apiSecret, position.symbol)
            if (amount != null && amount == 0.0) {
                positionRepository.update(position.symbol) { it.copy(isClosed = true) }
                break
            }
        }
    }
}