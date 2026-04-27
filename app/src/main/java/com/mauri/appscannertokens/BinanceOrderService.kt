package com.mauri.appscannertokens

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import okhttp3.FormBody
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

class BinanceOrderService(
    private val restClient: BinanceRestClient
) {
    private val precisionCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    suspend fun executeOrder(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        orderType: String,
        quantity: Double,
        price: Double,
        marginType: String
    ): OrderExecutionResult {
        setMarginType(apiKey, apiSecret, symbol, marginType)

        val (qtyPrecision, pricePrecision) = getPrecisions(symbol)
        val quantityStr = BigDecimal.valueOf(quantity)
            .setScale(qtyPrecision, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

        val params = mutableListOf(
            "symbol" to symbol,
            "side" to side,
            "type" to orderType,
            "quantity" to quantityStr
        )

        if (orderType == "LIMIT") {
            val priceStr = BigDecimal.valueOf(price)
                .setScale(pricePrecision, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            params += "price" to priceStr
            params += "timeInForce" to "GTC"
        }

        params += "recvWindow" to "60000"
        params += "timestamp" to System.currentTimeMillis().toString()

        val form = signedForm(params, apiSecret)
        val result = restClient.postForm(
            url = "https://fapi.binance.com/fapi/v1/order",
            body = form,
            apiKey = apiKey
        )

        return if (result.isSuccessful) {
            val orderId = JSONObject(result.body.ifBlank { "{}" }).optLong("orderId", 0L)
            OrderExecutionResult(true, "Orden $orderType de $symbol enviada", orderId)
        } else {
            val msg = JSONObject(result.body.ifBlank { "{}" }).optString("msg", "Error desconocido")
            OrderExecutionResult(false, "Error: $msg")
        }
    }

    suspend fun cancelOpenOrdersStrictly(apiKey: String, apiSecret: String, symbol: String): Boolean {
        cancelAllOpenOrders(apiKey, apiSecret, symbol)

        repeat(10) {
            if (openOrdersCount(apiKey, apiSecret, symbol) == 0) return true
            delay(200)
            cancelAllOpenOrders(apiKey, apiSecret, symbol)
        }

        return false
    }

    suspend fun placeClosePositionOrder(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        type: String,
        stopPrice: Double,
        clientOrderId: String = newProtectionClientOrderId(type, symbol)
    ): StopOrderResult {
        val (_, pricePrecision) = getPrecisions(symbol)
        val priceStr = BigDecimal.valueOf(stopPrice)
            .setScale(pricePrecision, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

        val params = listOf(
            "symbol" to symbol,
            "side" to side,
            "type" to type,
            "stopPrice" to priceStr,
            "closePosition" to "true",
            "workingType" to "MARK_PRICE",
            "newClientOrderId" to clientOrderId,
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )

        val result = restClient.postForm(
            url = "https://fapi.binance.com/fapi/v1/order",
            body = signedForm(params, apiSecret),
            apiKey = apiKey
        )

        return if (result.isSuccessful) {
            val json = JSONObject(result.body.ifBlank { "{}" })
            StopOrderResult(
                success = true,
                message = "$type colocado en $symbol",
                orderId = json.optLong("orderId", 0L),
                clientOrderId = json.optString("clientOrderId", clientOrderId),
                stopPrice = priceStr.toDoubleOrNull() ?: stopPrice
            )
        } else {
            val json = JSONObject(result.body.ifBlank { "{}" })
            StopOrderResult(
                success = false,
                message = json.optString("msg", "Error desconocido"),
                code = json.optInt("code", 0),
                clientOrderId = clientOrderId,
                stopPrice = priceStr.toDoubleOrNull() ?: stopPrice
            )
        }
    }

    suspend fun executeGuaranteedClose(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        quantity: Double,
        marginType: String
    ): Boolean {
        repeat(3) { attempt ->
            val result = executeOrder(
                apiKey = apiKey,
                apiSecret = apiSecret,
                symbol = symbol,
                side = side,
                orderType = "MARKET",
                quantity = quantity,
                price = 0.0,
                marginType = marginType
            )
            if (result.success) return true
            if (attempt < 2) delay(1500)
        }
        return false
    }

    suspend fun getOpenOrders(apiKey: String, apiSecret: String, symbol: String): List<OpenOrder>? {
        val params = listOf(
            "symbol" to symbol,
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        val result = restClient.get(
            url = "https://fapi.binance.com/fapi/v1/openOrders?${BinanceSigner.signedQuery(params, apiSecret)}",
            apiKey = apiKey
        )
        if (!result.isSuccessful || !result.body.startsWith("[")) return null

        return JsonParser.parseString(result.body).asJsonArray.map { item ->
            val obj = item.asJsonObject
            OpenOrder(
                orderId = obj.get("orderId").asLong,
                clientOrderId = obj.get("clientOrderId")?.asString.orEmpty(),
                symbol = obj.get("symbol")?.asString.orEmpty(),
                side = obj.get("side")?.asString.orEmpty(),
                type = obj.get("type")?.asString.orEmpty(),
                stopPrice = obj.get("stopPrice")?.asString?.toDoubleOrNull() ?: 0.0,
                closePosition = obj.get("closePosition")?.asBoolean ?: false
            )
        }
    }

    suspend fun cancelOpenOrdersExcept(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        keepOrderIds: Set<Long>
    ): Boolean {
        val openOrders = getOpenOrders(apiKey, apiSecret, symbol) ?: return false
        openOrders
            .filter { it.orderId !in keepOrderIds }
            .forEach { cancelOrder(apiKey, apiSecret, symbol, it.orderId) }

        repeat(10) {
            val remaining = getOpenOrders(apiKey, apiSecret, symbol) ?: return false
            if (remaining.all { it.orderId in keepOrderIds }) return true
            delay(200)
        }

        return false
    }

    suspend fun cancelOrder(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        orderId: Long
    ): Boolean {
        val params = listOf(
            "symbol" to symbol,
            "orderId" to orderId.toString(),
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        val result = restClient.delete(
            url = "https://fapi.binance.com/fapi/v1/order?${BinanceSigner.signedQuery(params, apiSecret)}",
            apiKey = apiKey
        )
        return result.isSuccessful
    }

    suspend fun findOpenStopLoss(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String
    ): OpenOrder? {
        return getOpenOrders(apiKey, apiSecret, symbol)
            ?.firstOrNull {
                it.type == "STOP_MARKET" &&
                    it.side == side &&
                    it.closePosition
            }
    }

    private suspend fun getPrecisions(symbol: String): Pair<Int, Int> {
        precisionCache[symbol]?.let { return it }

        val result = restClient.get("https://fapi.binance.com/fapi/v1/exchangeInfo")
        if (result.isSuccessful && result.body.isNotBlank()) {
            val symbols = JsonParser.parseString(result.body).asJsonObject.getAsJsonArray("symbols")
            for (i in 0 until symbols.size()) {
                val item = symbols[i].asJsonObject
                precisionCache[item.get("symbol").asString] =
                    item.get("quantityPrecision").asInt to item.get("pricePrecision").asInt
            }
        }

        return precisionCache[symbol] ?: (3 to 4)
    }

    private suspend fun setMarginType(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        marginType: String
    ) {
        val params = listOf(
            "symbol" to symbol,
            "marginType" to marginType,
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        restClient.postForm(
            url = "https://fapi.binance.com/fapi/v1/marginType",
            body = signedForm(params, apiSecret),
            apiKey = apiKey
        )
    }

    private suspend fun cancelAllOpenOrders(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ) {
        val params = listOf(
            "symbol" to symbol,
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        restClient.delete(
            url = "https://fapi.binance.com/fapi/v1/allOpenOrders?${BinanceSigner.signedQuery(params, apiSecret)}",
            apiKey = apiKey
        )
    }

    private suspend fun openOrdersCount(apiKey: String, apiSecret: String, symbol: String): Int? {
        return getOpenOrders(apiKey, apiSecret, symbol)?.size
    }

    private fun signedForm(params: List<Pair<String, String>>, apiSecret: String): FormBody {
        val signature = BinanceSigner.sign(BinanceSigner.query(params), apiSecret)
        val builder = FormBody.Builder()
        params.forEach { (key, value) -> builder.add(key, value) }
        builder.add("signature", signature)
        return builder.build()
    }

    private fun newProtectionClientOrderId(type: String, symbol: String): String {
        val prefix = if (type == "STOP_MARKET") "sl" else "tp"
        val suffix = System.currentTimeMillis().toString().takeLast(12)
        return "${prefix}_${symbol.lowercase()}_$suffix".take(36)
    }
}
