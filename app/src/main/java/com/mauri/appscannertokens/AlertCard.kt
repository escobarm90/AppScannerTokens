package com.mauri.appscannertokens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.google.gson.JsonParser

object BinanceApiManager {

    private val client = OkHttpClient()

    private val precisionCache = mutableMapOf<String, Pair<Int, Int>>()

    // =========================
    // FIRMA HMAC
    // =========================
    private fun crearFirma(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // =========================
    // OBTENER PRECISIONES
    // =========================
    suspend fun obtenerPrecisiones(symbol: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {

            if (precisionCache.containsKey(symbol)) {
                return@withContext precisionCache[symbol]!!
            }

            try {
                val request = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/exchangeInfo")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use

                    val json = JsonParser.parseString(body).asJsonObject
                    val symbols = json.getAsJsonArray("symbols")

                    for (i in 0 until symbols.size()) {
                        val obj = symbols[i].asJsonObject
                        val sym = obj.get("symbol").asString
                        val qtyPrec = obj.get("quantityPrecision").asInt
                        val pricePrec = obj.get("pricePrecision").asInt

                        precisionCache[sym] = Pair(qtyPrec, pricePrec)
                    }
                }
            } catch (_: Exception) {
            }

            return@withContext precisionCache[symbol] ?: Pair(3, 4)
        }

    // =========================
    // CREAR ESCUDO (SL / TP)
    // =========================
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
                .setScale(pricePrec, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()

            val ts = System.currentTimeMillis()

            val query = "symbol=$symbol&side=$side&type=$type&stopPrice=$priceStr" +
                    "&closePosition=true&workingType=MARK_PRICE" +
                    "&recvWindow=60000&timestamp=$ts"

            val form = FormBody.Builder()
                .add("symbol", symbol)
                .add("side", side)
                .add("type", type)
                .add("stopPrice", priceStr)
                .add("closePosition", "true")
                .add("workingType", "MARK_PRICE")
                .add("recvWindow", "60000")
                .add("timestamp", ts.toString())
                .add("signature", crearFirma(query, apiSecret))
                .build()

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/order")
                .post(form)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->

                val body = response.body?.string() ?: ""

                return@withContext if (response.isSuccessful) {
                    AlertManager.agregarLog("✅ Escudo $type colocado en $symbol")
                    true
                } else {
                    val json = JSONObject(body)
                    val msg = json.optString("msg", "Error desconocido")
                    val code = json.optInt("code", 0)

                    AlertManager.agregarLog("❌ Binance error ($type): $msg ($code)")

                    false
                }
            }

        } catch (e: Exception) {
            AlertManager.agregarLog("❌ Error red escudo: ${e.message}")
            return@withContext false
        }
    }

    // =========================
    // PRECIO ACTUAL
    // =========================
    suspend fun obtenerPrecioActual(symbol: String): Double =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JsonParser.parseString(
                            response.body?.string() ?: ""
                        ).asJsonObject

                        return@withContext json.get("price").asDouble
                    }
                }
            } catch (_: Exception) {
            }
            return@withContext 0.0
        }

    // =========================
    // OBTENER POSICIÓN
    // =========================
    suspend fun obtenerCantidadPosicion(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Double? = withContext(Dispatchers.IO) {

        try {
            val ts = System.currentTimeMillis()

            val query = "symbol=$symbol&timestamp=$ts"
            val firma = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/positionRisk?$query&signature=$firma")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->

                val body = response.body?.string() ?: return@use

                val arr = JsonParser.parseString(body).asJsonArray

                for (i in 0 until arr.size()) {
                    val obj = arr[i].asJsonObject
                    if (obj.get("symbol").asString == symbol) {
                        return@withContext obj.get("positionAmt").asDouble
                    }
                }
            }

        } catch (_: Exception) {
        }

        return@withContext null
    }

    // =========================
    // LIMPIAR ÓRDENES
    // =========================
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

    // =========================
    // CIERRE MARKET
    // =========================
    suspend fun ejecutarCierreGarantizado(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        quantity: Double,
        marginType: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val ts = System.currentTimeMillis()

            val qtyStr = quantity.toString()

            val query = "symbol=$symbol&side=$side&type=MARKET&quantity=$qtyStr&timestamp=$ts"

            val form = FormBody.Builder()
                .add("symbol", symbol)
                .add("side", side)
                .add("type", "MARKET")
                .add("quantity", qtyStr)
                .add("timestamp", ts.toString())
                .add("signature", crearFirma(query, apiSecret))
                .build()

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/order")
                .post(form)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }

        } catch (_: Exception) {
            return@withContext false
        }
    }
}