package com.mauri.appscannertokens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: ConfigViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        TopAppBar(
            title = { Text("Configuración", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { onBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Conexión Binance", color = Color(0xFF2EA043), fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = viewModel.apiKey,
                onValueChange = { viewModel.apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
            )

            OutlinedTextField(
                value = viewModel.secretKey,
                onValueChange = { viewModel.secretKey = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
            )

            Button(
                onClick = { viewModel.validarCredenciales() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                enabled = !viewModel.validandoApi
            ) {
                Text(if (viewModel.validandoApi) "Comprobando..." else "Validar API de Binance")
            }

            if (viewModel.mensajeValidacion.isNotEmpty()) {
                Text(viewModel.mensajeValidacion, color = if (viewModel.mensajeValidacion.contains("✅")) Color(0xFF2EA043) else Color(0xFFF85149), fontSize = 14.sp)
            }

            HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 8.dp))

            Text("Estrategia y Riesgo", color = Color(0xFF2EA043), fontWeight = FontWeight.Bold)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.timeframe,
                    onValueChange = { viewModel.timeframe = it },
                    label = { Text("Timeframe (ej: 3m)") },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
                OutlinedTextField(
                    value = viewModel.apalancamiento,
                    onValueChange = { viewModel.apalancamiento = it },
                    label = { Text("Apalancamiento (x)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.tamanoPosPct,
                    onValueChange = { viewModel.tamanoPosPct = it },
                    label = { Text("% Billetera a usar (Margen)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
                OutlinedTextField(
                    value = viewModel.perdidaMaxPct,
                    onValueChange = { viewModel.perdidaMaxPct = it },
                    label = { Text("% Pérdida Máx a asumir (SL)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.billeteraManual,
                    onValueChange = { viewModel.billeteraManual = it },
                    label = { Text("Billetera Simulada (USDT)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
                OutlinedTextField(
                    value = viewModel.roiMinimo,
                    onValueChange = { viewModel.roiMinimo = it },
                    label = { Text("ROI Mínimo (%)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )
            }

            Text("Tipo de Margen", color = Color.White, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.tipoMargen = "ISOLATED" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.tipoMargen == "ISOLATED") Color(0xFF2EA043) else Color(0xFF30363D)
                    )
                ) {
                    Text("Aislado", color = Color.White)
                }
                Button(
                    onClick = { viewModel.tipoMargen = "CROSS" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.tipoMargen == "CROSS") Color(0xFF2EA043) else Color(0xFF30363D)
                    )
                ) {
                    Text("Cruzado", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.guardarConfiguracion()
                    Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EA043))
            ) {
                Text("Guardar y Volver", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
