package com.mauri.appscannertokens

import java.util.concurrent.ConcurrentHashMap

class SignalValidator(
    private val marketDataService: BinanceMarketDataService,
    private val cooldownRegistry: MutableMap<String, Long> = ConcurrentHashMap()
) {
    suspend fun validate(signal: TechnicalSignal): SignalValidationResult {
        val orderBook = marketDataService.fetchOrderBook(signal.symbol)
            ?: return SignalValidationResult.Rejected("API ERROR: No se pudo leer Orderbook de ${signal.symbol}")
        ScannerDebugLogger.d(
            "${signal.symbol} orderbook: bidQty=${orderBook.bidQty}, askQty=${orderBook.askQty}, side=${signal.side}"
        )

        if (signal.side == "LONG" && orderBook.askQty > orderBook.bidQty * 3.5) {
            return SignalValidationResult.Rejected("ESTADO: RECHAZADO - MURO DE VENTA")
        }
        if (signal.side == "SHORT" && orderBook.bidQty > orderBook.askQty * 3.5) {
            return SignalValidationResult.Rejected("ESTADO: RECHAZADO - MURO DE COMPRA")
        }

        val tradeFlow = marketDataService.fetchTradeFlow(signal.symbol)
            ?: return SignalValidationResult.Rejected("API ERROR: No se pudo leer flujo de trades de ${signal.symbol}")
        ScannerDebugLogger.d(
            "${signal.symbol} trades: totalQty=${tradeFlow.totalQty}, buyPercent=${String.format(java.util.Locale.US, "%.2f", tradeFlow.buyPercent)}%"
        )

        if (signal.side == "LONG" && tradeFlow.buyPercent < 40.0) {
            return SignalValidationResult.Rejected(
                "ESTADO: RECHAZADO - FUERZA INSUFICIENTE EN TRADES (${String.format(java.util.Locale.US, "%.1f", tradeFlow.buyPercent)}%)"
            )
        }
        if (signal.side == "SHORT" && tradeFlow.buyPercent > 60.0) {
            return SignalValidationResult.Rejected(
                "ESTADO: RECHAZADO - FUERZA INSUFICIENTE EN TRADES (${String.format(java.util.Locale.US, "%.1f", tradeFlow.buyPercent)}%)"
            )
        }

        val now = System.currentTimeMillis()
        val last = cooldownRegistry[signal.symbol] ?: 0L
        if (now - last < COOLDOWN_MS) {
            return SignalValidationResult.Rejected(
                "ESTADO: IGNORADO - COOLDOWN (Esperando ${(COOLDOWN_MS - (now - last)) / 1000}s)"
            )
        }

        return SignalValidationResult.Valid
    }

    fun markAccepted(symbol: String) {
        cooldownRegistry[symbol] = System.currentTimeMillis()
    }

    private companion object {
        const val COOLDOWN_MS = 300_000L
    }
}
