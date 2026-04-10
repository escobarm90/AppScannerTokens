package com.mauri.appscannertokens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConfigScreen(
    currentConfig: UserConfig, // Asumimos que pasas la config desde el ViewModel o SharedPreferences
    onSaveConfig: (UserConfig) -> Unit
) {
    var config by remember { mutableStateOf(currentConfig) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text("📡 AppScannerTokens - Bot", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58a6ff))
        Spacer(modifier = Modifier.height(16.dp))

        // --- CREDENCIALES ---
        Text("🔑 Credenciales Binance", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { config = config.copy(apiKey = it) },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.apiSecret,
            onValueChange = { config = config.copy(apiSecret = it) },
            label = { Text("API Secret") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // --- GESTIÓN DE CAPITAL ---
        Text("🏦 Gestión de Capital", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.porcentajeInversion.toString(),
                onValueChange = { config = config.copy(porcentajeInversion = it.toDoubleOrNull() ?: 0.3) },
                label = { Text("% Inversión (Ej: 0.3)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = config.apalancamiento.toString(),
                onValueChange = { config = config.copy(apalancamiento = it.toIntOrNull() ?: 20) },
                label = { Text("Apalancamiento") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // --- ESTRATEGIA SCALPER ---
        Text("⚙️ Estrategia Scalper", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.multiplicadorSl.toString(),
                onValueChange = { config = config.copy(multiplicadorSl = it.toDoubleOrNull() ?: 1.5) },
                label = { Text("Multiplicador SL (ATR)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = config.multiplicadorTp.toString(),
                onValueChange = { config = config.copy(multiplicadorTp = it.toDoubleOrNull() ?: 1.0) },
                label = { Text("Ratio TP (R:B)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.minRatioVol.toString(),
                onValueChange = { config = config.copy(minRatioVol = it.toDoubleOrNull() ?: 0.6) },
                label = { Text("Min Ratio Vol") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = config.minVolatilidadPct.toString(),
                onValueChange = { config = config.copy(minVolatilidadPct = it.toDoubleOrNull() ?: 0.25) },
                label = { Text("Min Volatilidad %") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSaveConfig(config) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2ea043), contentColor = Color.White)
        ) {
            Text("GUARDAR Y REINICIAR MOTOR", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}