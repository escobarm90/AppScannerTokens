package com.mauri.appscannertokens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class ConfigActivity : ComponentActivity() {

    // Inicializamos el ViewModel
    private val viewModel: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                // 1. Observamos el estado (la configuración actual) desde el ViewModel
                val currentConfig by viewModel.configState.collectAsState()

                // 2. Llamamos a la pantalla con los parámetros correctos
                ConfigScreen(
                    currentConfig = currentConfig,
                    onTestConnection = { config -> viewModel.probarConexion(config) },
                    onSaveConfig = { configModificada ->
                        viewModel.guardarConfiguracion(configModificada)
                        finish()
                    }
                )
            }
        }
    }
}
