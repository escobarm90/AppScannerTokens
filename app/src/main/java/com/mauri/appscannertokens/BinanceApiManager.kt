package com.mauri.appscannertokens

import android.util.Log
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

    // Encriptación obligatoria para Binance
    private fun crearFirma(data: String, secret: String): String {
        val hmacSha256 = "HmacSHA256"
        val mac = Mac.getInstance(hmacSha256)
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), hmacSha256)
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // 1. Botón de Testear Conexión (Consulta el balance de la cuenta)
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
                    Pair(true, "¡Conexión Exitosa!")
                } else {
                    val errorMsg = JSONObject(body).optString("msg", "Error desconocido")
                    Pair(false, "Fallo: $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Error de red: ${e.message}")
        }
    }

    // 2. Ejecutar la orden completa (Cambia margen y dispara orden)
    suspend fun ejecutarOrden(
        apiKey: String, apiSecret: String, symbol: String, side: String,
        orderType: String, quantity: Double, price: Double, marginType: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // A. Cambiar Tipo de Margen
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
                // El error -4046 significa "Ya estaba en ese margen", lo cual está perfecto, lo ignoramos.
                if (!res.isSuccessful && !body.contains("-4046")) {
                    Log.e("BinanceAPI", "Error Margen: $body")
                }
            }

            // B. Enviar la Orden
            val ts2 = System.currentTimeMillis()
            val qtyStr = String.format(Locale.US, "%.3f", quantity) // Binance requiere pocos decimales en QTY
            val priceStr = String.format(Locale.US, "%.5f", price)

            var queryOrden = "symbol=$symbol&side=$side&type=$orderType&quantity=$qtyStr&timestamp=$ts2"
            if (orderType == "LIMIT") queryOrden += "&price=$priceStr&timeInForce=GTC"

            val sigOrden = crearFirma(queryOrden, apiSecret)

            val formBuilder = FormBody.Builder()
                .add("symbol", symbol).add("side", side).add("type", orderType)
                .add("quantity", qtyStr).add("timestamp", ts2.toString()).add("signature", sigOrden)
            if (orderType == "LIMIT") {
                formBuilder.add("price", priceStr).add("timeInForce", "GTC")
            }

            val reqOrden = Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/order")
                .post(formBuilder.build())
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(reqOrden).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) Pair(true, "¡Orden $orderType enviada con éxito!")
                else Pair(false, "Error: ${JSONObject(body).optString("msg")}")
            }
        } catch (e: Exception) {
            Pair(false, "Excepción: ${e.message}")
        }
    }
}