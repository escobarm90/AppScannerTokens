package com.mauri.appscannertokens

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
