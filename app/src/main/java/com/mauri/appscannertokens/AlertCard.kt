package com.mauri.appscannertokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AlertCard(
    alerta: AlertData,
    billetera: Double = 20.0, // Saldo simulado o real
    onDismiss: () -> Unit
) {
    var porcentajeUsado by remember { mutableStateOf(30f) } // Por defecto 30%
    var tipoMargen by remember { mutableStateOf("ISOLATED") }

    val colorFondo = Color(0xFF161b22)
    val colorBorde = Color(0xFF30363d)
    val colorGreen = Color(0xFF2ea043)
    val colorRed = Color(0xFFda3633)
    val colorCyan = Color(0xFF58a6ff)
    val colorYellow = Color(0xFFe3b341)

    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val horaStr = formatter.format(Date(alerta.timestamp))

    // Cálculos en vivo
    val margenCalculado = billetera * (porcentajeUsado / 100)
    // Asumimos un Ratio 1:1 para el PNL visual (se ajusta a tu config real luego)
    val pnlCalculado = margenCalculado * 1.5

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colorFondo),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // HEADER
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("🪙 ${alerta.symbol}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("🕒 $horaStr", color = Color.Gray, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .background(if (alerta.senal == "LONG") colorGreen.copy(alpha = 0.15f) else colorRed.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .border(1.dp, if (alerta.senal == "LONG") colorGreen else colorRed, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("🎯 ${alerta.senal}", color = if (alerta.senal == "LONG") colorGreen else colorRed, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // DATA ROWS
            DataRow("💵 ENTRADA", String.format(Locale.US, "%.6f", alerta.precio), colorCyan)
            DataRow("✅ TAKE PROFIT", String.format(Locale.US, "%.6f", alerta.tp), colorGreen)
            DataRow("❌ STOP LOSS", String.format(Locale.US, "%.6f", alerta.sl), colorRed)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorde)

            // EJECUTAR ORDEN RÁPIDA
            Text("⚡ EJECUTAR ORDEN RÁPIDA", color = colorCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Invertir: ${porcentajeUsado.toInt()}% de tu billetera", color = colorYellow, fontSize = 14.sp)
            Slider(
                value = porcentajeUsado,
                onValueChange = { porcentajeUsado = it },
                valueRange = 5f..100f,
                steps = 19,
                colors = SliderDefaults.colors(thumbColor = colorCyan, activeTrackColor = colorCyan)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { /* Lógica API Binance Limit */ },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colorCyan)
                ) {
                    Text("📝 LIMIT", color = colorFondo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = { /* Lógica API Binance Market */ },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colorGreen)
                ) {
                    Text("🚀 MARKET", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
            ) {
                Text("❌ DESCARTAR SEÑAL", color = Color.LightGray)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorde)

            // RESULTADOS ESTIMADOS
            Text("🏦 RESULTADOS ESTIMADOS", color = colorCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            DataRow("Margen a Usar", String.format(Locale.US, "$%.2f USDT", margenCalculado), colorCyan)
            DataRow("PNL Neto Est.", String.format(Locale.US, "$%.2f USDT", pnlCalculado), colorGreen)
        }
    }
}

@Composable
fun DataRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}