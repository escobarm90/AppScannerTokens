package com.mauri.appscannertokens.data.repository

import com.mauri.appscannertokens.domain.model.AlertData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AlertRepository {
    private val _alerts = MutableStateFlow<List<AlertData>>(emptyList())
    val alerts: StateFlow<List<AlertData>> = _alerts.asStateFlow()

    private val _newAlertEvent = MutableSharedFlow<AlertData>(extraBufferCapacity = 1)
    val newAlertEvent = _newAlertEvent.asSharedFlow()

    fun add(alert: AlertData) {
        _alerts.update { current -> listOf(alert) + current }
        _newAlertEvent.tryEmit(alert)
    }

    fun remove(alert: AlertData) {
        _alerts.update { current -> current.filter { it != alert } }
    }
    
    fun clear() {
        _alerts.value = emptyList()
    }
}
