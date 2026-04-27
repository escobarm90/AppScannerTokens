package com.mauri.appscannertokens

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BinanceSigner {
    fun sign(data: String, secret: String): String {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(), algorithm))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun query(params: List<Pair<String, String>>): String =
        params.joinToString("&") { "${it.first}=${it.second}" }

    fun signedQuery(params: List<Pair<String, String>>, secret: String): String {
        val unsigned = query(params)
        return "$unsigned&signature=${sign(unsigned, secret)}"
    }
}
