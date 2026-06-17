package com.mauri.appscannertokens.data.remote

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import com.google.gson.JsonParser

class BinanceMarketDataService(
    private val restClient: BinanceRestClient
) {
    suspend fun fetchTopPerpetualUsdtSymbols(
        limit: Int = 50,
        minQuoteVolume: Double = 15_000_000.0
    ): List<String> {
        val result = restClient.get("https://fapi.binance.com/fapi/v1/ticker/24hr")
        if (!result.isSuccessful || !result.body.startsWith("[")) {
            return listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        }

        return JsonParser.parseString(result.body).asJsonArray
            .map { it.asJsonObject }
            .filter {
                val symbol = it.get("symbol").asString
                val quoteVolume = it.get("quoteVolume").asString.toDoubleOrNull() ?: 0.0
                symbol.endsWith("USDT") && !symbol.contains("_") && quoteVolume > minQuoteVolume
            }
            .sortedByDescending {
                kotlin.math.abs(it.get("priceChangePercent").asString.toDoubleOrNull() ?: 0.0)
            }
            .take(limit)
            .map { it.get("symbol").asString }
    }

    suspend fun fetchKlines(symbol: String, timeframe: String, limit: Int = 300): List<KlineData> {
        val result = restClient.get(
            "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=$limit"
        )
        if (!result.isSuccessful || !result.body.startsWith("[")) return emptyList()

        val klines = JsonParser.parseString(result.body).asJsonArray
        return (0 until klines.size()).map { index ->
            val kline = klines[index].asJsonArray
            KlineData(
                open = kline[1].asString.toDoubleOrNull() ?: 0.0,
                high = kline[2].asString.toDoubleOrNull() ?: 0.0,
                low = kline[3].asString.toDoubleOrNull() ?: 0.0,
                close = kline[4].asString.toDoubleOrNull() ?: 0.0,
                volume = kline[5].asString.toDoubleOrNull() ?: 0.0,
                closeTime = kline[6].asLong
            )
        }
    }

    suspend fun fetchOrderBook(symbol: String): OrderBookSnapshot? {
        val result = restClient.get("https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5")
        if (!result.isSuccessful || !result.body.startsWith("{")) return null

        val obj = JsonParser.parseString(result.body).asJsonObject
        var bidQty = 0.0
        var askQty = 0.0
        obj.getAsJsonArray("bids").forEach { bidQty += it.asJsonArray[1].asDouble }
        obj.getAsJsonArray("asks").forEach { askQty += it.asJsonArray[1].asDouble }
        return OrderBookSnapshot(bidQty = bidQty, askQty = askQty)
    }

    suspend fun fetchTradeFlow(symbol: String): TradeFlowSnapshot? {
        val result = restClient.get("https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=500")
        if (!result.isSuccessful || !result.body.startsWith("[")) return null

        val trades = JsonParser.parseString(result.body).asJsonArray
        var totalQty = 0.0
        var buyQty = 0.0
        trades.forEach {
            val trade = it.asJsonObject
            val qty = trade.get("qty").asDouble
            val isBuyerMaker = trade.get("isBuyerMaker").asBoolean
            totalQty += qty
            if (!isBuyerMaker) buyQty += qty
        }

        val buyPercent = if (totalQty > 0) (buyQty / totalQty) * 100.0 else 50.0
        return TradeFlowSnapshot(totalQty = totalQty, buyPercent = buyPercent)
    }

    suspend fun fetchCurrentPrice(symbol: String): Double {
        val result = restClient.get("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol")
        if (!result.isSuccessful || result.body.isBlank()) return 0.0
        return JsonParser.parseString(result.body).asJsonObject.get("price").asDouble
    }

    suspend fun fetchSpreads(symbols: Set<String>): Map<String, Double> {
        val result = restClient.get("https://fapi.binance.com/fapi/v1/ticker/bookTicker")
        if (!result.isSuccessful || !result.body.startsWith("[")) return emptyMap()

        val spreads = mutableMapOf<String, Double>()
        val tickers = JsonParser.parseString(result.body).asJsonArray
        for (i in 0 until tickers.size()) {
            val ticker = tickers[i].asJsonObject
            val symbol = ticker.get("symbol").asString
            if (symbol !in symbols) continue

            val bid = ticker.get("bidPrice").asString.toDoubleOrNull() ?: 0.0
            val ask = ticker.get("askPrice").asString.toDoubleOrNull() ?: 0.0
            if (bid > 0.0) {
                spreads[symbol] = ((ask - bid) / bid) * 100.0
            }
        }
        return spreads
    }
}
