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

// 1. Modelo de los Indicadores
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

// 2. Modelo de la Alerta Visual
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