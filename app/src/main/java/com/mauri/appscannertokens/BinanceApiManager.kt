package com.mauri.appscannertokens

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

object BinanceApiManager {
    private val client = OkHttpClient()

    // --- MOTOR WEBSOCKET CACHÉ (0.3s) ---
    val preciosWs = ConcurrentHashMap<String, Double>()

    // CACHÉ DE DECIMALES PARA NO SATURAR LA API
    val precisionCache = ConcurrentHashMap<String, Pair<Int, Int>>()
    private var tickerWs: WebSocket? = null

    fun iniciarWsTickers() {
        if (tickerWs != null) return
        val request = Request.Builder().url("wss://fstream.binance.com/ws/!ticker@arr").build()
        tickerWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val arr = JsonParser.parseString(text).asJsonArray
                    for (i in 0 until arr.size()) {
                        val obj = arr.get(i).asJsonObject
                        preciosWs[obj.get("s").asString] = obj.get("c").asDouble
                    }
                } catch (e: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                tickerWs = null
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: okhttp3.Response?
            ) {
                tickerWs = null
                Thread.sleep(5000)
                iniciarWsTickers()
            }
        })
    }

    private fun crearFirma(data: String, secret: String): String {
        val hmacSha256 = "HmacSHA256"
        val mac = Mac.getInstance(hmacSha256)
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), hmacSha256)
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun obtenerPrecisiones(symbol: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (precisionCache.containsKey(symbol)) return@withContext precisionCache[symbol]!!

        try {
            val req = Request.Builder().url("https://fapi.binance.com/fapi/v1/exchangeInfo").get().build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@use Pair(3, 4)
                    val json = JsonParser.parseString(body).asJsonObject
                    val symbols = json.getAsJsonArray("symbols")
                    for (i in 0 until symbols.size()) {
                        val symObj = symbols.get(i).asJsonObject
                        val sym = symObj.get("symbol").asString
                        val qtyPrec = symObj.get("quantityPrecision").asInt
                        val pricePrec = symObj.get("pricePrecision").asInt
                        precisionCache[sym] = Pair(qtyPrec, pricePrec)
                    }
                    return@use precisionCache[symbol] ?: Pair(3, 4)
                }
            }
        } catch (_: Exception) {
        }
        return@withContext Pair(3, 4)
    }

    suspend fun obtenerSaldoUSDT(apiKey: String, apiSecret: String): Double =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiSecret.isEmpty()) return@withContext 0.0
            try {
                val ts = System.currentTimeMillis()
                val query = "recvWindow=60000&timestamp=$ts"
                val sig = crearFirma(query, apiSecret)
                val req = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$sig")
                    .addHeader("X-MBX-APIKEY", apiKey).build()
                val result = client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val balances =
                            JsonParser.parseString(res.body?.string() ?: "[]").asJsonArray
                        for (i in 0 until balances.size()) {
                            val asset = balances.get(i).asJsonObject
                            if (asset.get("asset").asString == "USDT") return@use asset.get("availableBalance").asDouble
                        }
                        0.0
                    } else {
                        0.0
                    }
                }
                return@withContext result
            } catch (e: Exception) {
            }
            return@withContext 0.0
        }

    suspend fun probarConexion(apiKey: String, apiSecret: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val ts = System.currentTimeMillis()
                val query = "recvWindow=60000&timestamp=$ts"
                val sig = crearFirma(query, apiSecret)
                val req = Request.Builder()
                    .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$sig")
                    .addHeader("X-MBX-APIKEY", apiKey).build()
                val result = client.newCall(req).execute().use { res ->
                    val body = res.body?.string() ?: ""
                    if (res.isSuccessful) {
                        Pair(true, "¡Conexión Exitosa con Binance Futuros!")
                    } else {
                        val errorMsg = JSONObject(body).optString("msg", "Error desconocido")
                        Pair(false, "Fallo: $errorMsg")
                    }
                }
                return@withContext result
            } catch (e: Exception) {
                return@withContext Pair(false, "Error de red: ${e.message}")
            }
        }

    suspend fun ejecutarOrden(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        orderType: String,
        quantity: Double,
        price: Double,
        marginType: String
    ): Triple<Boolean, String, Long> = withContext(Dispatchers.IO) {
        try {
            try {
                val ts1 = System.currentTimeMillis()
                val q1 = "symbol=$symbol&marginType=$marginType&recvWindow=60000&timestamp=$ts1"
                val reqMargen = Request.Builder().url("https://fapi.binance.com/fapi/v1/marginType")
                    .post(
                        FormBody.Builder().add("symbol", symbol).add("marginType", marginType)
                            .add("recvWindow", "60000").add("timestamp", ts1.toString())
                            .add("signature", crearFirma(q1, apiSecret)).build()
                    ).addHeader("X-MBX-APIKEY", apiKey).build()
                client.newCall(reqMargen).execute().close()
            } catch (e: Exception) {
            }

            val ts2 = System.currentTimeMillis()
            val (qtyPrec, pricePrec) = obtenerPrecisiones(symbol)
            val qtyStr = BigDecimal.valueOf(quantity).setScale(qtyPrec, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString()
            val priceStr =
                BigDecimal.valueOf(price).setScale(pricePrec, java.math.RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString()

            var qOrden = "symbol=$symbol&side=$side&type=$orderType&quantity=$qtyStr"
            val form =
                FormBody.Builder().add("symbol", symbol).add("side", side).add("type", orderType)
                    .add("quantity", qtyStr)
            if (orderType == "LIMIT") {
                qOrden += "&price=$priceStr&timeInForce=GTC"
                form.add("price", priceStr).add("timeInForce", "GTC")
            }
            qOrden += "&recvWindow=60000&timestamp=$ts2"
            form.add("recvWindow", "60000").add("timestamp", ts2.toString())
                .add("signature", crearFirma(qOrden, apiSecret))

            val req =
                Request.Builder().url("https://fapi.binance.com/fapi/v1/order").post(form.build())
                    .addHeader("X-MBX-APIKEY", apiKey).build()
            val result = client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val orderId = JSONObject(body).optLong("orderId", 0L)
                    Triple(true, "¡Orden $orderType de $symbol enviada!", orderId)
                } else {
                    Triple(false, "Error: ${JSONObject(body).optString("msg")}", 0L)
                }
            }
            return@withContext result
        } catch (e: Exception) {
            return@withContext Triple(false, "Excepción: ${e.message}", 0L)
        }
    }

    suspend fun obtenerEstadoOrden(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        orderId: Long
    ): String = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val q = "symbol=$symbol&orderId=$orderId&recvWindow=60000&timestamp=$ts"
            val req = Request.Builder().url(
                "https://fapi.binance.com/fapi/v1/order?$q&signature=${
                    crearFirma(
                        q,
                        apiSecret
                    )
                }"
            ).addHeader("X-MBX-APIKEY", apiKey).get().build()
            val result = client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    JSONObject(res.body?.string() ?: "").optString("status", "UNKNOWN")
                } else {
                    "UNKNOWN"
                }
            }
            return@withContext result
        } catch (e: Exception) {
        }
        return@withContext "UNKNOWN"
    }

    suspend fun obtenerCantidadPosicion(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Double? = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val q = "symbol=$symbol&recvWindow=60000&timestamp=$ts"
            val req = Request.Builder().url(
                "https://fapi.binance.com/fapi/v2/positionRisk?$q&signature=${
                    crearFirma(
                        q,
                        apiSecret
                    )
                }"
            ).addHeader("X-MBX-APIKEY", apiKey).build()
            val result = client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful && body.startsWith("[")) {
                    val arr = JsonParser.parseString(body).asJsonArray
                    var posReal = 0.0
                    for (i in 0 until arr.size()) {
                        val amt = arr.get(i).asJsonObject.get("positionAmt").asDouble
                        if (kotlin.math.abs(amt) > 0) posReal = amt
                    }
                    posReal
                } else {
                    null
                }
            }
            return@withContext result
        } catch (e: Exception) {
        }
        return@withContext null
    }

    suspend fun obtenerPrecioActual(symbol: String): Double = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol").build()
            val result = client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    JsonParser.parseString(
                        res.body?.string() ?: ""
                    ).asJsonObject.get("price").asDouble
                } else {
                    0.0
                }
            }
            return@withContext result
        } catch (e: Exception) {
        }
        return@withContext 0.0
    }

    suspend fun cancelarOrdenes(apiKey: String, apiSecret: String, symbol: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val ts = System.currentTimeMillis()
                val q = "symbol=$symbol&recvWindow=60000&timestamp=$ts"
                val req = Request.Builder().url("https://fapi.binance.com/fapi/v1/allOpenOrders")
                    .delete(
                        FormBody.Builder().add("symbol", symbol).add("recvWindow", "60000")
                            .add("timestamp", ts.toString())
                            .add("signature", crearFirma(q, apiSecret)).build()
                    ).addHeader("X-MBX-APIKEY", apiKey).build()
                val result = client.newCall(req).execute().use { res ->
                    res.isSuccessful
                }
                return@withContext result
            } catch (e: Exception) {
                return@withContext false
            }
        }

    suspend fun limpiarOrdenesEstrictamente(
        apiKey: String,
        apiSecret: String,
        symbol: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            cancelarOrdenes(apiKey, apiSecret, symbol)
        } catch (_: Exception) {
        }
        repeat(10) {
            try {
                val ts = System.currentTimeMillis()
                val q = "symbol=$symbol&recvWindow=60000&timestamp=$ts"
                val req = Request.Builder().url(
                    "https://fapi.binance.com/fapi/v1/openOrders?$q&signature=${
                        crearFirma(
                            q,
                            apiSecret
                        )
                    }"
                ).addHeader("X-MBX-APIKEY", apiKey).get().build()
                val result = client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val arr = JsonParser.parseString(res.body?.string() ?: "[]").asJsonArray
                        if (arr.size() == 0) return@use true
                        for (j in 0 until arr.size()) {
                            val orderId = arr.get(j).asJsonObject.get("orderId").asLong
                            try {
                                val delTs = System.currentTimeMillis()
                                val delReq =
                                    Request.Builder().url("https://fapi.binance.com/fapi/v1/order")
                                        .delete(
                                            FormBody.Builder().add("symbol", symbol)
                                                .add("orderId", orderId.toString())
                                                .add("recvWindow", "60000")
                                                .add("timestamp", delTs.toString()).add(
                                                "signature",
                                                crearFirma(
                                                    "symbol=$symbol&orderId=$orderId&recvWindow=60000&timestamp=$delTs",
                                                    apiSecret
                                                )
                                            ).build()
                                        ).addHeader("X-MBX-APIKEY", apiKey).build()
                                client.newCall(delReq).execute().close()
                            } catch (_: Exception) {
                            }
                        }
                        false
                    } else {
                        false
                    }
                }
                if (result) return@withContext true
            } catch (_: Exception) {
            }
            kotlinx.coroutines.delay(200)
        }
        return@withContext false
    }

    suspend fun crearOrdenStop(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        type: String,
        stopPrice: Double
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val (_, pricePrec) = obtenerPrecisiones(symbol)
            val priceStr =
                BigDecimal.valueOf(stopPrice).setScale(pricePrec, java.math.RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString()

            val q =
                "symbol=$symbol&side=$side&type=$type&stopPrice=$priceStr&closePosition=true&workingType=CONTRACT_PRICE&recvWindow=60000&timestamp=$ts"
            val form = FormBody.Builder().add("symbol", symbol).add("side", side).add("type", type)
                .add("stopPrice", priceStr).add("closePosition", "true")
                .add("workingType", "CONTRACT_PRICE").add("recvWindow", "60000")
                .add("timestamp", ts.toString()).add("signature", crearFirma(q, apiSecret))
            val req =
                Request.Builder().url("https://fapi.binance.com/fapi/v1/order").post(form.build())
                    .addHeader("X-MBX-APIKEY", apiKey).build()

            val result = client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    val errorMsg = JSONObject(body).optString("msg", "Error Desconocido")
                    val errorCode = JSONObject(body).optInt("code", 0)
                    AlertManager.agregarLog("❌ RECHAZO BINANCE ($type): $errorMsg (Cod: $errorCode)")
                    Pair(false, body)
                } else {
                    Pair(true, body)
                }
            }
            return@withContext result
        } catch (e: Exception) {
            return@withContext Pair(false, "Error de red: ${e.message}")
        }
    }

    suspend fun crearEscudoGarantizado(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        type: String,
        stopPrice: Double,
        quantity: Double,
        marginType: String
    ): Boolean {
        for (i in 1..3) {
            val (exito, response) = crearOrdenStop(apiKey, apiSecret, symbol, side, type, stopPrice)
            if (exito) return true

            try {
                val json = JSONObject(response)
                val code = json.optInt("code", 0)

                if (code == -2021) {
                    AlertManager.agregarLog("⚠️ [CRÍTICO] $type para $symbol se dispararía de inmediato. Ejecutando CIERRE MARKET de emergencia...")
                    val (mktExito, mktMsj, _) = ejecutarOrden(apiKey, apiSecret, symbol, side, "MARKET", quantity, 0.0, marginType)
                    if (mktExito) return true
                    AlertManager.agregarLog("❌ Falló cierre market de emergencia: $mktMsj")
                }
            } catch (e: Exception) {}

            AlertManager.agregarLog("🔄 Reintentando colocar escudo $type para $symbol (Intento $i/3)...")
            kotlinx.coroutines.delay(1000)
        }
        return false
    }

    suspend fun ejecutarCierreGarantizado(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String,
        quantity: Double,
        marginType: String
    ): Boolean {
        for (i in 1..3) {
            val (exito, msj, _) = ejecutarOrden(apiKey, apiSecret, symbol, side, "MARKET", quantity, 0.0, marginType)
            if (exito) return true
            AlertManager.agregarLog("⚠️ Reintentando cierre MARKET $symbol (Intento $i/3): $msj")
            kotlinx.coroutines.delay(1500)
        }
        return false
    }
}
