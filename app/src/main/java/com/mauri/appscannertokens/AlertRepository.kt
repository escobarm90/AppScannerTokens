package com.mauri.appscannertokens

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mauri.appscannertokens.domain.model.AlertData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.collections.emptyList

object AlertRepository {
    private val alertsState = MutableStateFlow<List<AlertData>>(emptyList())
    val alerts: StateFlow<List<AlertData>> = alertsState.asStateFlow()

    private val gson = Gson()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val json = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_ALERTS, null)
        if (json != null) {
            val type = object : TypeToken<List<AlertData>>() {}.type
            alertsState.value = gson.fromJson(json, type)
        }
    }

    fun add(alert: AlertData) {
        ensureInitialized()
        alertsState.update { current ->
            if (current.firstOrNull()?.let { it.symbol == alert.symbol && it.senal == alert.senal } == true) {
                current
            } else {
                (listOf(alert) + current).take(5).also { persist(it) }
            }
        }
    }

    fun remove(alert: AlertData) {
        alertsState.update { current ->
            current.filter { it != alert }.also { persist(it) }
        }
    }

    private fun ensureInitialized() {
        val context = appContext ?: return
        if (alertsState.value.isEmpty()) initialize(context)
    }

    private fun persist(alerts: List<AlertData>) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_ALERTS, gson.toJson(alerts))
            ?.apply()
    }

    private const val PREFS_NAME = "AlertasHistorial"
    private const val KEY_ALERTS = "lista"
}
