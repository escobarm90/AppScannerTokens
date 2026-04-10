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
        try {
            val timestamp = System.currentTimeMillis()
            val query = "timestamp=$timestamp"
            val signature = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$signature")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use 0.0
                if (response.isSuccessful) {
                    val balances = JsonParser.parseString(body).asJsonArray
                    for (b in balances) {
                        val asset = b.asJsonObject
                        if (asset.get("asset").asString == "USDT") {
                            return@use asset.get("balance").asDouble
                        }
                    }
                }
                0.0 // <--- AQUÍ ESTÁ LA SOLUCIÓN AL ERROR: Le decimos qué devolver por defecto
            }
        } catch (e: Exception) {
            Log.e("BinanceAPI", "Error obteniendo saldo: ${e.message}")
        }
        return@withContext 0.0
    }

    // 2. Probar Conexión desde Configuración
    suspend fun probarConexion(apiKey: String, apiSecret: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val query = "timestamp=$timestamp"
            val signature = crearFirma(query, apiSecret)

            val request = Request.Builder()
                .url("https://fapi.binance.com/fapi/v2/balance?$query&signature=$signature")
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Pair(true, "¡Conexión Exitosa con Binance!")
                } else {
                    val errorMsg = JSONObject(body).optString("msg", "Error desconocido")
                    Pair(false, "Fallo: $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Error de red: ${e.message}")
        }
    }

    // 3. Enviar Orden de Compra/Venta
    suspend fun ejecutarOrden(
        apiKey: String, apiSecret: String, symbol: String, side: String,
        orderType: String, quantity: Double, price: Double, marginType: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // A. Cambiar Margen
            val ts1 = System.currentTimeMillis()
            val queryMargen = "symbol=$symbol&marginType=$marginType&timestamp=$ts1"
            val sigMargen = crearFirma(queryMargen, apiSecret)

            val reqMargen = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/marginType")
                .post(FormBody.Builder().add("symbol", symbol).add("marginType", marginType).add("timestamp", ts1.toString()).add("signature", sigMargen).build())
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(reqMargen).execute().use { res ->
                val body = res.body?.string() ?: ""
                // Ignoramos el error -4046 que tira Binance si ya estabas en ese modo de margen
                if (!res.isSuccessful && !body.contains("-4046")) Log.e("BinanceAPI", "Error Margen: $body")
            }

            // B. Enviar Orden
            val ts2 = System.currentTimeMillis()
            val qtyStr = String.format(Locale.US, "%.3f", quantity)
            val priceStr = String.format(Locale.US, "%.5f", price)

            var queryOrden = "symbol=$symbol&side=$side&type=$orderType&quantity=$qtyStr&timestamp=$ts2"
            if (orderType == "LIMIT") queryOrden += "&price=$priceStr&timeInForce=GTC"

            val sigOrden = crearFirma(queryOrden, apiSecret)

            val formBuilder = FormBody.Builder()
                .add("symbol", symbol).add("side", side).add("type", orderType)
                .add("quantity", qtyStr).add("timestamp", ts2.toString()).add("signature", sigOrden)

            if (orderType == "LIMIT") formBuilder.add("price", priceStr).add("timeInForce", "GTC")

            val reqOrden = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/order")
                .post(formBuilder.build())
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(reqOrden).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) Pair(true, "¡Orden $orderType de $symbol enviada!")
                else Pair(false, "Error: ${JSONObject(body).optString("msg")}")
            }
        } catch (e: Exception) {
            Pair(false, "Excepción: ${e.message}")
        }
    }
}