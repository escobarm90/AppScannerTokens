package com.mauri.appscannertokens.data.repository

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LogRepository {
    private val logsState = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = logsState.asStateFlow()

    fun add(message: String) {
        logsState.update { current -> (listOf(message) + current).take(100) }
    }
}
