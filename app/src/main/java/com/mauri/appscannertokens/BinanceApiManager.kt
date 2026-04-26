package com.mauri.appscannertokens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.google.gson.JsonParser

object BinanceApiManager {

    private val client = OkHttpClient()
    private val precisionCache = mutableMapOf<String, Pair<Int, Int>>()

    // ================= FIRMA =================
    private fun crearFirma(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(key)
        return mac.doFinal(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // ================= PRECISIONES =================
    suspend fun obtenerPrecisiones(symbol: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {

            val cached = precisionCache[symbol]
            if (cached != null) return@withContext cached

            try {
                val request = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/exchangeInfo")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (body != null) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val symbols = json.getAsJsonArray("symbols")

                    for (i in 0 until symbols.size()) {
                        val obj = symbols[i].asJsonObject
                        val sym = obj.get("symbol").asString
                        val qty = obj.get("quantityPrecision").asInt
                        val price = obj.get("pricePrecision").asInt

                        precisionCache[sym] = Pair(qty, price)
                    }
                }

                response.close()
            } catch (_: Exception) {}

            return@withContext precisionCache[symbol] ?: Pair(3, 4)
        }

    // ================= CREAR SL / TP =================
    suspend fun crearEscudoGarantizado(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        type: String,
        stopPrice: Double
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val precisiones = obtenerPrecisiones(symbol)
            val pricePrec = precisiones.second

            val priceStr = BigDecimal.valueOf(stopPrice)
                .setScale(pricePrec, BigDecimal.ROUND_HALF_UP)
                .stripTrailingZeros()
                .toPlainString()

            val ts = System.currentTimeMillis()

            val query = "symbol=$symbol&side=$side&type=$type&stopPrice=$priceStr" +
                    "&closePosition=true&workingType=MARK_PRICE" +
                    "&timestamp=$ts"

            val form = FormBody.Builder()
                .add("symbol", symbol)
                .add("side", side)
                .add("type", type)
                .add("stopPrice", priceStr)
                .add("closePosition", "true")
                .add("workingType", "MARK_PRICE")
                .add("timestamp", ts.toString())
                .add("signature", crearFirma(query, apiSecret))
                .build()

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/order")
                .post(form)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful) {
                AlertManager.agregarLog("✅ $type colocado en $symbol")
                response.close()
                return@withContext true
            } else {
                val msg = JSONObject(body ?: "{}").optString("msg")
                AlertManager.agregarLog("❌ Binance: $msg")
                response.close()
                return@withContext false
            }

        } catch (e: Exception) {
            AlertManager.agregarLog("❌ Error red: ${e.message}")
            return@withContext false
        }
    }

    // ================= PRECIO =================
    suspend fun obtenerPrecioActual(symbol: String): Double =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol")
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string()

                if (body != null) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val price = json.get("price").asDouble
                    res.close()
                    return@withContext price
                }

                res.close()
            } catch (_: Exception) {}

            return@withContext 0.0
        }

    // ================= POSICIÓN =================
    suspend fun obtenerCantidadPosicion(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Double? = withContext(Dispatchers.IO) {

        try {
            val ts = System.currentTimeMillis()
            val query = "timestamp=$ts"
            val firma = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/positionRisk?$query&signature=$firma")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (body != null) {
                val arr = JsonParser.parseString(body).asJsonArray

                for (i in 0 until arr.size()) {
                    val obj = arr[i].asJsonObject
                    if (obj.get("symbol").asString == symbol) {
                        val amt = obj.get("positionAmt").asDouble
                        response.close()
                        return@withContext amt
                    }
                }
            }

            response.close()
        } catch (_: Exception) {}

        return@withContext null
    }

    // ================= LIMPIAR =================
    suspend fun limpiarOrdenesEstrictamente(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val ts = System.currentTimeMillis()
            val query = "symbol=$symbol&timestamp=$ts"
            val firma = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/allOpenOrders?$query&signature=$firma")
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().close()
            return@withContext true

        } catch (_: Exception) {
            return@withContext false
        }
    }
}