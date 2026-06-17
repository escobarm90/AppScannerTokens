package com.mauri.appscannertokens.data.remote

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.json.JSONObject
import kotlin.math.abs

class BinanceAccountService(
    private val restClient: BinanceRestClient
) {
    suspend fun getAvailableUsdtBalance(apiKey: String, apiSecret: String): Double {
        return getUsdtBalanceField(apiKey, apiSecret, "availableBalance")
    }

    suspend fun getTotalUsdtBalance(apiKey: String, apiSecret: String): Double {
        return getUsdtBalanceField(apiKey, apiSecret, "balance")
    }

    private suspend fun getUsdtBalanceField(apiKey: String, apiSecret: String, fieldName: String): Double {
        if (apiKey.isBlank() || apiSecret.isBlank()) return 0.0

        val result = restClient.get(
            url = "https://fapi.binance.com/fapi/v2/balance?${signedTimestampQuery(apiSecret)}",
            apiKey = apiKey
        )
        if (!result.isSuccessful || !result.body.startsWith("[")) return 0.0

        val balances = JsonParser.parseString(result.body).asJsonArray
        for (i in 0 until balances.size()) {
            val asset = balances[i].asJsonObject
            if (asset.get("asset").asString == "USDT") {
                return asset.get(fieldName)?.asDouble ?: 0.0
            }
        }
        return 0.0
    }

    suspend fun testConnection(apiKey: String, apiSecret: String): Pair<Boolean, String> {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return false to "Ingresa las credenciales"
        }

        val result = restClient.get(
            url = "https://fapi.binance.com/fapi/v2/balance?${signedTimestampQuery(apiSecret)}",
            apiKey = apiKey
        )

        return if (result.isSuccessful) {
            true to "Conexion exitosa con Binance Futuros"
        } else {
            val msg = JSONObject(result.body.ifBlank { "{}" }).optString("msg", "Error desconocido")
            false to "Fallo: $msg"
        }
    }

    suspend fun getOrderStatus(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        orderId: Long
    ): String {
        val params = listOf(
            "symbol" to symbol,
            "orderId" to orderId.toString(),
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        val result = restClient.get(
            url = "https://fapi.binance.com/fapi/v1/order?${BinanceSigner.signedQuery(params, apiSecret)}",
            apiKey = apiKey
        )
        if (!result.isSuccessful) return "UNKNOWN"
        return JSONObject(result.body.ifBlank { "{}" }).optString("status", "UNKNOWN")
    }

    suspend fun getPositionAmount(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Double? = getPositionSnapshot(apiKey, apiSecret, symbol)?.positionAmt

    suspend fun getPositionSnapshot(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): BinancePositionSnapshot? {
        val params = listOf(
            "symbol" to symbol,
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        val result = restClient.get(
            url = "https://fapi.binance.com/fapi/v2/positionRisk?${BinanceSigner.signedQuery(params, apiSecret)}",
            apiKey = apiKey
        )
        if (!result.isSuccessful || result.body.isBlank()) return null

        return parsePositionSnapshot(JsonParser.parseString(result.body), symbol)
    }

    private fun signedTimestampQuery(apiSecret: String): String {
        val params = listOf(
            "recvWindow" to "60000",
            "timestamp" to System.currentTimeMillis().toString()
        )
        return BinanceSigner.signedQuery(params, apiSecret)
    }

    private fun parsePositionSnapshot(json: JsonElement, symbol: String): BinancePositionSnapshot? {
        if (json.isJsonArray) {
            val positions = json.asJsonArray
            for (i in 0 until positions.size()) {
                val obj = positions[i].asJsonObject
                if (obj.get("symbol").asString == symbol) {
                    return obj.toPositionSnapshot()
                }
            }
            return BinancePositionSnapshot(symbol, 0.0, 0.0, 0.0)
        }

        if (json.isJsonObject) {
            val obj = json.asJsonObject
            if (obj.has("symbol") && obj.get("symbol").asString == symbol) {
                return obj.toPositionSnapshot()
            }
        }

        return null
    }

    private fun com.google.gson.JsonObject.toPositionSnapshot(): BinancePositionSnapshot =
        BinancePositionSnapshot(
            symbol = get("symbol").asString,
            positionAmt = get("positionAmt").asDouble,
            entryPrice = get("entryPrice")?.asDouble ?: 0.0,
            markPrice = get("markPrice")?.asDouble ?: 0.0
        )

    suspend fun hasOpenPosition(apiKey: String, apiSecret: String, symbol: String): Boolean? {
        return getPositionAmount(apiKey, apiSecret, symbol)?.let { abs(it) > 0.0 }
    }
}
