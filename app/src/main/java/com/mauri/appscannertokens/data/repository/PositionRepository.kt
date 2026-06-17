package com.mauri.appscannertokens.data.repository

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object PositionRepository {
    private val positionsState = MutableStateFlow<List<ActivePosition>>(emptyList())
    val positions: StateFlow<List<ActivePosition>> = positionsState.asStateFlow()

    private val gson = Gson()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val json = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_POSITIONS, null)
        if (json != null) {
            val type = object : TypeToken<List<ActivePosition>>() {}.type
            positionsState.value = gson.fromJson(json, type)
        }
    }

    fun activePositions(): List<ActivePosition> =
        positionsState.value.filter { !it.isClosed }

    fun add(position: ActivePosition) {
        positionsState.update { current ->
            if (current.any { it.symbol == position.symbol && !it.isClosed }) current else current + position
        }
        persist()
    }

    fun update(symbol: String, transform: (ActivePosition) -> ActivePosition) {
        positionsState.update { current ->
            current.map { position -> if (position.symbol == symbol) transform(position) else position }
        }
        persist()
    }

    fun remove(symbol: String) {
        positionsState.update { current -> current.filter { it.symbol != symbol } }
        persist()
    }

    private fun persist() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_POSITIONS, gson.toJson(positionsState.value))
            ?.apply()
    }

    private const val PREFS_NAME = "PositionsPrefs"
    private const val KEY_POSITIONS = "active_positions"
}
