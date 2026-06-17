package com.mauri.appscannertokens.presentation.viewmodel

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mauri.appscannertokens.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ConfigRepository

    private val _configState = MutableStateFlow(UserConfig())
    val configState: StateFlow<UserConfig> = _configState.asStateFlow()

    init {
        AppGraph.initialize(application)
        repository = AppGraph.configRepository
        _configState.value = repository.load()
    }

    fun guardarConfiguracion(nuevaConfig: UserConfig) {
        repository.save(nuevaConfig)
        _configState.value = nuevaConfig
    }

    suspend fun probarConexion(config: UserConfig): String {
        val (_, message) = AppGraph.accountService.testConnection(config.apiKey, config.apiSecret)
        return message
    }
}
