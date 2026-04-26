package com.mauri.appscannertokens

import org.ta4j.core.BaseBarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.adx.ADXIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.helpers.VolumeIndicator
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.num.DoubleNum

class TokenData(symbol: String) {
    val series: BaseBarSeries = BaseBarSeriesBuilder()
        .withName(symbol)
        .withNumTypeOf(DoubleNum::class.java)
        .build().apply {
            maximumBarCount = 400
        }

    val closePrice = ClosePriceIndicator(series)
    val volumeInd = VolumeIndicator(series)

    val rsi = RSIIndicator(closePrice, 14)
    val atr = ATRIndicator(series, 7)
    val ema7 = EMAIndicator(closePrice, 7)
    val ema200 = EMAIndicator(closePrice, 200)
    val volSma = SMAIndicator(volumeInd, 20)
    val adx = ADXIndicator(series, 14)

    val stdDev = StandardDeviationIndicator(closePrice, 20)
    val sma20 = SMAIndicator(closePrice, 20)
    val bbMiddle = BollingerBandsMiddleIndicator(sma20)
    val bbUpper = BollingerBandsUpperIndicator(bbMiddle, stdDev, series.numOf(2.0))
    val bbLower = BollingerBandsLowerIndicator(bbMiddle, stdDev, series.numOf(2.0))

    val macdLine = MACDIndicator(closePrice, 12, 26)
    val macdSignal = EMAIndicator(macdLine, 9)
}

data class AlertData(
    val symbol: String,
    val senal: String,
    val precio: Double,
    val tp: Double,
    val sl: Double,
    val velasEstimadas: Int,
    val timeframe: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ActivePosition(
    val symbol: String,
    val side: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val initialTp: Double,
    val dynamicTp: Double,
    val currentSl: Double,
    val trailingLevel: Int = 0,
    val pnlNeto: Double = 0.0,
    val roePct: Double = 0.0,
    val isClosed: Boolean = false,
    val apalancamiento: Int,
    val orderId: Long,
    val quantity: Double
)

data class KlineData(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

data class OrderBookSnapshot(
    val bidQty: Double,
    val askQty: Double
)

data class TradeFlowSnapshot(
    val totalQty: Double,
    val buyPercent: Double
)

data class TechnicalSignal(
    val symbol: String,
    val side: String,
    val entryPrice: Double,
    val atr: Double,
    val spreadPct: Double,
    val timeframe: String
)

sealed class StrategyAnalysis {
    abstract val debugLog: String

    data class Signal(
        override val debugLog: String,
        val signal: TechnicalSignal
    ) : StrategyAnalysis()

    data class Rejected(
        override val debugLog: String,
        val reason: String
    ) : StrategyAnalysis()
}

sealed class SignalValidationResult {
    data object Valid : SignalValidationResult()
    data class Rejected(val reason: String) : SignalValidationResult()
}

sealed class RiskCalculationResult {
    data class Approved(val alert: AlertData) : RiskCalculationResult()
    data class Rejected(val reason: String) : RiskCalculationResult()
}

data class OrderExecutionResult(
    val success: Boolean,
    val message: String,
    val orderId: Long = 0L
)

data class StopOrderResult(
    val success: Boolean,
    val message: String,
    val code: Int = 0
)

data class OrderSizing(
    val marginUsdt: Double,
    val notionalUsdt: Double,
    val quantity: Double,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val roePct: Double,
    val entryFeeUsdt: Double,
    val exitFeeUsdt: Double,
    val riskWasReduced: Boolean
)

data class AlertExecutionRequest(
    val alert: AlertData,
    val walletPercent: Double,
    val marginType: String
)

data class TrailingUpdate(
    val newTp: Double,
    val newSl: Double,
    val newLevel: Int
)
