package com.mauri.appscannertokens.data.remote

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


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
