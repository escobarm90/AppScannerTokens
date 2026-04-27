package com.mauri.appscannertokens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class BinanceHttpResult(
    val isSuccessful: Boolean,
    val code: Int,
    val body: String
)

class BinanceRestClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun get(url: String, apiKey: String? = null): BinanceHttpResult =
        execute(Request.Builder().url(url).get(), apiKey)

    suspend fun postForm(url: String, body: FormBody, apiKey: String? = null): BinanceHttpResult =
        execute(Request.Builder().url(url).post(body), apiKey)

    suspend fun delete(url: String, apiKey: String? = null): BinanceHttpResult =
        execute(Request.Builder().url(url).delete(), apiKey)

    private suspend fun execute(builder: Request.Builder, apiKey: String?): BinanceHttpResult =
        withContext(Dispatchers.IO) {
            if (!apiKey.isNullOrBlank()) {
                builder.addHeader("X-MBX-APIKEY", apiKey)
            }

            client.newCall(builder.build()).execute().use { response ->
                BinanceHttpResult(
                    isSuccessful = response.isSuccessful,
                    code = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }
}
