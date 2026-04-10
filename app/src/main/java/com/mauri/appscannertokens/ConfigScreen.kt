package com.mauri.appscannertokens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    currentConfig: UserConfig,
    onSaveConfig: (UserConfig) -> Unit
) {
    // Clonamos la configuración actual para poder editarla en la pantalla
    var config by remember { mutableStateOf(currentConfig) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isTesting by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    // Paleta de colores Dark Mode Premium
    val colorFondo = Color(0xFF0d1117)
    val colorCard = Color(0xFF161b22)
    val colorPrimary = Color(0xFF58a6ff)
    val colorBorde = Color(0xFF30363d)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorFondo)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("⚙️ Configuración del Bot", color = colorPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Ajusta los parámetros y credenciales", color = Color.Gray, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(20.dp))

        // --- TARJETA DE CREDENCIALES BINANCE ---
        Card(colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🔑 Credenciales API Binance", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { config = config.copy(apiKey = it) },
                    label = { Text("API Key", color = Color.Gray) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedBorderColor = colorPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = config.apiSecret,
                    onValueChange = { config = config.copy(apiSecret = it) },
                    label = { Text("API Secret", color = Color.Gray) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedBorderColor = colorPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (passwordVisible) "Ocultar Keys" else "👁️ Mostrar Keys",
                    color = colorPrimary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { passwordVisible = !passwordVisible }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (config.apiKey.isEmpty() || config.apiSecret.isEmpty()) {
                            Toast.makeText(context, "Ingresa las credenciales", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isTesting = true
                        coroutineScope.launch {
                            val (exito, msj) = BinanceApiManager.probarConexion(config.apiKey, config.apiSecret)
                            Toast.makeText(context, msj, Toast.LENGTH_LONG).show()
                            isTesting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ea043)),
                    enabled = !isTesting
                ) {
                    Text(if (isTesting) "Verificando..." else "🔌 Probar Conexión", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- TARJETA DE GESTIÓN DE RIESGO ---
        Card(colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚖️ Gestión de Riesgo", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                NumberStepper("Apalancamiento (x)", config.apalancamiento.toDouble(), 1.0, 1.0) {
                    config = config.copy(apalancamiento = it.toInt())
                }
                NumberStepper("% de Billetera por Trade", config.porcentajeInversion, 1.0, 1.0) {
                    config = config.copy(porcentajeInversion = it)
                }
                NumberStepper("Multiplicador Take Profit", config.multiplicadorTp, 0.1, 0.1) {
                    config = config.copy(multiplicadorTp = it)
                }
                NumberStepper("Multiplicador Stop Loss", config.multiplicadorSl, 0.1, 0.1) {
                    config = config.copy(multiplicadorSl = it)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- TARJETA DE INDICADORES ---
        Card(colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📈 Filtros Técnicos", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                NumberStepper("Mínimo Ratio Volumen", config.minRatioVol, 0.1, 0.1) {
                    config = config.copy(minRatioVol = it)
                }
                NumberStepper("Volatilidad Mínima ATR (%)", config.minVolatilidadPct, 0.05, 0.05) {
                    config = config.copy(minVolatilidadPct = it)
                }
                NumberStepper("Spread Máximo Permitido (%)", config.maxSpreadPct, 0.05, 0.05) {
                    config = config.copy(maxSpreadPct = it)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- BOTÓN GUARDAR ---
        Button(
            onClick = {
                onSaveConfig(config) // Enviamos la config actualizada a la Activity
                Toast.makeText(context, "Configuración Guardada", Toast.LENGTH_SHORT).show()
                (context as Activity).finish() // Cerramos la pantalla
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorPrimary)
        ) {
            Text("💾 GUARDAR Y VOLVER", color = colorFondo, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

// COMPONENTE PERSONALIZADO: Controles visuales de Más/Menos
@Composable
fun NumberStepper(label: String, value: Double, step: Double, minVal: Double, onValueChange: (Double) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF30363d), RoundedCornerShape(4.dp))
                    .clickable { if (value - step >= minVal) onValueChange(Math.round((value - step) * 100.0) / 100.0) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("−", color = Color.White, fontWeight = FontWeight.Bold) }

            Text(
                text = if (step == 1.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp).width(45.dp)
            )

            Box(
                modifier = Modifier
                    .background(Color(0xFF30363d), RoundedCornerShape(4.dp))
                    .clickable { onValueChange(Math.round((value + step) * 100.0) / 100.0) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("+", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}