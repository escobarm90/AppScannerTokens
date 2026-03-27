package com.mauri.appscannertokens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels // Importante para inicializar el ViewModel
import androidx.compose.material3.MaterialTheme

class ConfigActivity : ComponentActivity() {

    // Instanciamos el ViewModel que contiene toda la lógica de configuración
    private val configViewModel: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Pasamos el viewModel y la acción para volver atrás
                ConfigScreen(
                    viewModel = configViewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}