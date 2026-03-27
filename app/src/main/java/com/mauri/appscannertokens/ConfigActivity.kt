package com.mauri.appscannertokens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ConfigActivity : ComponentActivity() {

    // Instanciamos el ViewModel correctamente
    private val configViewModel: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfigScreen(
                    viewModel = configViewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Text("Configuración", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = viewModel.apiKey,
            onValueChange = { viewModel.apiKey = it },
            label = { Text("API Key", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = viewModel.secretKey,
            onValueChange = { viewModel.secretKey = it },
            label = { Text("Secret Key", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.validarCredenciales() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EA043))
        ) {
            Text(if (viewModel.validandoApi) "Validando..." else "Validar API y Guardar")
        }

        if (viewModel.mensajeValidacion.isNotEmpty()) {
            Text(
                text = viewModel.mensajeValidacion,
                color = if (viewModel.mensajeValidacion.contains("✅")) Color.Green else Color.Red,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(thickness = 1.dp, color = Color(0xFF30363D))
        Spacer(modifier = Modifier.height(16.dp))

        Text("Estrategia de Trading", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = viewModel.timeframe,
            onValueChange = { viewModel.timeframe = it },
            label = { Text("Temporalidad (Ej: 1m, 3m)", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = viewModel.apalancamiento,
                onValueChange = { viewModel.apalancamiento = it },
                label = { Text("Apalancamiento (x)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = viewModel.roiMinimo,
                onValueChange = { viewModel.roiMinimo = it },
                label = { Text("ROI Mínimo (%)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = viewModel.tamanoPosPct,
                onValueChange = { viewModel.tamanoPosPct = it },
                label = { Text("Tamaño Posición (%)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = viewModel.perdidaMaxPct,
                onValueChange = { viewModel.perdidaMaxPct = it },
                label = { Text("Pérdida Máxima (%)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Tipo de Margen:", color = Color.Gray, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = viewModel.tipoMargen == "ISOLATED",
                onClick = {
                    viewModel.tipoMargen = "ISOLATED"
                    viewModel.guardarConfiguracion()
                },
                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2EA043))
            )
            Text("Aislado", color = Color.White)

            Spacer(modifier = Modifier.width(16.dp))

            RadioButton(
                selected = viewModel.tipoMargen == "CROSSED",
                onClick = {
                    viewModel.tipoMargen = "CROSSED"
                    viewModel.guardarConfiguracion()
                },
                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2EA043))
            )
            Text("Cruzado", color = Color.White)
        }
    }
}