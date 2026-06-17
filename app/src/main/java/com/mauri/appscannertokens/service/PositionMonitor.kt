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
    private val trailingStopEngine: TrailingStopEngine,
    private val stopLossGuard: StopLossGuard // Se mantiene por inyección pero NO SE USARA PARA CERRAR
) {
    suspend fun run(isResume: Boolean) = coroutineScope {
        var position = initialPosition

        logRepository.add("Monitor Live [${position.symbol}]. Control y cierres delegados a BINANCE.")

        while (isActive && !position.isClosed) {
            val currentPrice = tickerWebSocket.price(position.symbol)

            if (currentPrice != null && currentPrice > 0) {
                // 1. Actualizar métricas visuales (Ganancia, Precio en Vivo, etc)
                val isLong = position.side == "LONG"
                val pnlNeto = if (isLong) {
                    (currentPrice - position.entryPrice) * position.quantity
                } else {
                    (position.entryPrice - currentPrice) * position.quantity
                }
                val margin = (position.entryPrice * position.quantity) / position.apalancamiento
                val roePct = if (margin > 0) (pnlNeto / margin) * 100 else 0.0

                position = position.copy(
                    currentPrice = currentPrice,
                    pnlNeto = pnlNeto,
                    roePct = roePct
                )
                positionRepository.update(position.symbol) { position }

                // 2. Verificar Trailing Stop (Mover el SL real en los servidores de Binance)
                val newSl = trailingStopEngine.calculateNewStopLoss(position, currentPrice)

                if (newSl != null && newSl != position.currentSl) {
                    val newLevel = trailingStopEngine.getNewLevel(position, currentPrice)
                    logRepository.add(">>> Trailing activado al $newLevel%. Moviendo SL físico a $newSl")

                    val closeSide = if (isLong) "SELL" else "BUY"
                    val openOrders = orderService.getOpenOrders(config.apiKey, config.apiSecret, position.symbol)

                    // Buscar la orden vieja de Stop Loss en Binance
                    val slOrder = openOrders?.firstOrNull { it.type == "STOP_MARKET" && it.closePosition }

                    if (slOrder != null) {
                        // Paso A: Borrar SL viejo
                        val canceled = orderService.cancelOrder(config.apiKey, config.apiSecret, position.symbol, slOrder.orderId)
                        if (canceled) {
                            // Paso B: Colocar SL nuevo para asegurar la ganancia local
                            val slResult = orderService.placeStopLoss(config.apiKey, config.apiSecret, position.symbol, closeSide, newSl)
                            if (slResult.success) {
                                logRepository.add("EXITO: SL movido en Binance. Ganancia asegurada.")
                                position = position.copy(currentSl = newSl, trailingLevel = newLevel)
                                positionRepository.update(position.symbol) { position }
                            } else {
                                logRepository.add("EMERGENCIA FATAL: Se borró el SL viejo pero Binance rechazó el nuevo: ${slResult.message}. Forzando cierre para proteger capital.")
                                emergencyClose(position, closeSide)
                                break
                            }
                        } else {
                            logRepository.add("ERROR: No se pudo cancelar el SL anterior en Binance. Reintentando en próximo ciclo.")
                        }
                    } else {
                        logRepository.add("EMERGENCIA: No se encontró el SL original en Binance. Alguien lo borró manual o falló API. Forzando cierre.")
                        emergencyClose(position, closeSide)
                        break
                    }
                }
            }

            // 3. Revisar cada 4 segundos si Binance ya cerró la posición porque tocó el SL o TP reales.
            delay(4000)
            val amount = accountService.getPositionAmount(config.apiKey, config.apiSecret, position.symbol)
            if (amount != null && amount == 0.0) {
                logRepository.add("=========================================")
                logRepository.add("BINANCE CERRÓ POSICIÓN: ${position.symbol} (Tocó SL o TP en exchange).")
                logRepository.add("=========================================")
                position = position.copy(isClosed = true)
                positionRepository.update(position.symbol) { position }
                orderService.cancelOpenOrdersStrictly(config.apiKey, config.apiSecret, position.symbol)
                break
            }
        }
    }

    // Único método autorizado para cerrar a mercado: SOLO EMERGENCIAS de comunicación.
    private suspend fun emergencyClose(position: ActivePosition, closeSide: String) {
        logRepository.add("EJECUTANDO PROTOCOLO DE EMERGENCIA: Cierre a Mercado de ${position.symbol}")
        orderService.executeGuaranteedClose(
            apiKey = config.apiKey,
            apiSecret = config.apiSecret,
            symbol = position.symbol,
            side = closeSide,
            quantity = position.quantity,
            marginType = config.tipoMargen
        )
        positionRepository.update(position.symbol) { it.copy(isClosed = true) }
        orderService.cancelOpenOrdersStrictly(config.apiKey, config.apiSecret, position.symbol)
    }
}