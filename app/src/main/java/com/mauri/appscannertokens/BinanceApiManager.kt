package com.mauri.appscannertokens

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Locale
import java.math.BigDecimal

object BinanceApiManager {
    private val client = OkHttpClient()

    private fun crearFirma(data: String, secret: String): String {
        val hmacSha256 = "HmacSHA256"
        val mac = Mac.getInstance(hmacSha256)
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), hmacSha256)
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // 1. Consultar Saldo Real de USDT
    suspend fun obtenerSaldoUSDT(apiKey: String, apiSecret: String): Double = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiSecret.isEmpty()) return@withContext 0.0

        var saldoDisponible = 0.0
        try {
            val timestamp = System.currentTimeMillis()
            val query = "recvWindow=60000&timestamp=$timestamp"
            val signature = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$signature")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val balances = JsonParser.parseString(body).asJsonArray
                    for (i in 0 until balances.size()) {
                        val asset = balances.get(i).asJsonObject
                        if (asset.get("asset").asString == "USDT") {
                            saldoDisponible = asset.get("availableBalance").asDouble
                            break
                        }
                    }
                } else {
                    Log.e("BinanceAPI", "Error consultando saldo: $body")
                }
                0.0 // Retorno por defecto para el bloque .use
            }
        } catch (e: Exception) {
            Log.e("BinanceAPI", "Error obteniendo saldo: ${e.message}")
        }
        return@withContext saldoDisponible
    }

    // 2. Probar Conexión
    suspend fun probarConexion(apiKey: String, apiSecret: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val query = "recvWindow=60000&timestamp=$timestamp"
            val signature = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$signature")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Pair(true, "¡Conexión Exitosa con Binance Futuros!")
                } else {
                    val errorMsg = JSONObject(body).optString("msg", "Error desconocido")
                    Pair(false, "Fallo: $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Error de red: ${e.message}")
        }
    }

    // 3. Enviar Orden Inicial (Market/Limit)
    suspend fun ejecutarOrden(
        apiKey: String, apiSecret: String, symbol: String, side: String,
        orderType: String, quantity: Double, price: Double, marginType: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val ts1 = System.currentTimeMillis()
            val queryMargen = "symbol=$symbol&marginType=$marginType&recvWindow=60000&timestamp=$ts1"
            val sigMargen = crearFirma(queryMargen, apiSecret)

            val reqMargen = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/marginType")
                .post(FormBody.Builder().add("symbol", symbol).add("marginType", marginType).add("recvWindow", "60000").add("timestamp", ts1.toString()).add("signature", sigMargen).build())
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(reqMargen).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (!res.isSuccessful && !body.contains("-4046")) Log.e("BinanceAPI", "Error Margen: $body")
            }

            val ts2 = System.currentTimeMillis()
            val qtyStr = BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()
            val priceStr = BigDecimal.valueOf(price).stripTrailingZeros().toPlainString()

            // 1. Armamos la cadena base
            var queryOrden = "symbol=$symbol&side=$side&type=$orderType&quantity=$qtyStr"

            // 2. Si es LIMIT, agregamos los extras inmediatamente
            if (orderType == "LIMIT") {
                queryOrden += "&price=$priceStr&timeInForce=GTC"
            }

            // 3. Cerramos SIEMPRE con recvWindow y timestamp al final
            queryOrden += "&recvWindow=60000&timestamp=$ts2"

            // 4. Firmamos la cadena completa y correcta
            val sigOrden = crearFirma(queryOrden, apiSecret)

            // 5. Armamos el FormBody en el MISMO orden exacto
            val formBuilder = FormBody.Builder()
                .add("symbol", symbol)
                .add("side", side)
                .add("type", orderType)
                .add("quantity", qtyStr)

            if (orderType == "LIMIT") {
                formBuilder.add("price", priceStr)
                formBuilder.add("timeInForce", "GTC")
            }

            formBuilder.add("recvWindow", "60000")
            formBuilder.add("timestamp", ts2.toString())
            formBuilder.add("signature", sigOrden)

            val reqOrden = Request.Builder().url("https://fapi.binance.com/fapi/v1/order").post(formBuilder.build()).addHeader("X-MBX-APIKEY", apiKey).build()

            client.newCall(reqOrden).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) Pair(true, "¡Orden $orderType de $symbol enviada!")
                else Pair(false, "Error: ${JSONObject(body).optString("msg")}")
            }
        } catch (e: Exception) { Pair(false, "Excepción: ${e.message}") }
    }

    // ==========================================
    // 4. FUNCIONES PARA TRAILING STOP EXPANSIVO
    // ==========================================

    suspend fun obtenerCantidadPosicion(apiKey: String, apiSecret: String, symbol: String): Double = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val query = "symbol=$symbol&recvWindow=60000&timestamp=$ts"
            val sig = crearFirma(query, apiSecret)
            val req = Request.Builder().url("https://fapi.binance.com/fapi/v2/positionRisk?$query&signature=$sig").addHeader("X-MBX-APIKEY", apiKey).build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val arr = JsonParser.parseString(res.body?.string()).asJsonArray
                    if (arr.size() > 0) return@use arr[0].asJsonObject.get("positionAmt").asDouble
                }
                0.0
            }
        } catch (e: Exception) {}
        return@withContext 0.0
    }

    suspend fun obtenerPrecioActual(symbol: String): Double = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$symbol").build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) return@use JsonParser.parseString(res.body?.string()).asJsonObject.get("price").asDouble
                0.0
            }
        } catch (e: Exception) {}
        return@withContext 0.0
    }

    suspend fun cancelarOrdenes(apiKey: String, apiSecret: String, symbol: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val query = "symbol=$symbol&recvWindow=60000&timestamp=$ts"
            val sig = crearFirma(query, apiSecret)
            val req = Request.Builder().url("https://fapi.binance.com/fapi/v1/allOpenOrders").delete(FormBody.Builder().add("symbol", symbol).add("recvWindow", "60000").add("timestamp", ts.toString()).add("signature", sig).build()).addHeader("X-MBX-APIKEY", apiKey).build()
            client.newCall(req).execute().use { return@use it.isSuccessful }
        } catch (e: Exception) { return@withContext false }
    }

    suspend fun crearOrdenStop(apiKey: String, apiSecret: String, symbol: String, side: String, type: String, stopPrice: Double): Boolean = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val priceStr = BigDecimal.valueOf(stopPrice).setScale(6, java.math.RoundingMode.HALF_DOWN).stripTrailingZeros().toPlainString()
            val form = FormBody.Builder().add("symbol", symbol).add("side", side).add("type", type).add("stopPrice", priceStr).add("closePosition", "true").add("workingType", "CONTRACT_PRICE").add("recvWindow", "60000").add("timestamp", ts.toString())
            val query = "symbol=$symbol&side=$side&type=$type&stopPrice=$priceStr&closePosition=true&workingType=CONTRACT_PRICE&recvWindow=60000&timestamp=$ts"
            val sig = crearFirma(query, apiSecret)
            form.add("signature", sig)

            val req = Request.Builder().url("https://fapi.binance.com/fapi/v1/order").post(form.build()).addHeader("X-MBX-APIKEY", apiKey).build()
            client.newCall(req).execute().use { return@use it.isSuccessful }
        } catch (e: Exception) { return@withContext false }
    }
}