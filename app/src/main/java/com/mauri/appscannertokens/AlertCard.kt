package com.mauri.appscannertokens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Estructura de datos para las alertas
data class AlertaData(
    val token: String,
    val senal: String,
    val hora: String,
    val precio: String,
    val margen: String,
    val apalancamiento: String,
    val tp: String,
    val sl: String,
    val roi: String,
    val pnlNeto: String
)

@Composable
fun AlertCard(alerta: AlertaData) {
    val isLong = alerta.senal == "LONG"
    val mainColor = if (isLong) Color(0xFF2EA043) else Color(0xFFF85149)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // FILA 1: TOKEN GIGANTE Y HORA
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alerta.token,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = alerta.hora,
                    color = Color(0xFF8B949E),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // FILA 2: SEÑAL Y PNL ESTIMADO
            Text(
                text = "${if (isLong) "🟢" else "🔴"} ${alerta.senal}  |  PNL: $${alerta.pnlNeto} USDT",
                color = mainColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 12.dp))

            // FILA 3: CUADRÍCULA DE DATOS EXACTA
            Row(modifier = Modifier.fillMaxWidth()) {
                // Columna Izquierda
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataRow("ENTRADA:", alerta.precio)
                    DataRow("TP:", alerta.tp)
                    DataRow("SL:", alerta.sl)
                }

                // Columna Derecha
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataRow("MARGEN:", "$${alerta.margen} USDT")
                    DataRow("APALANCAMIENTO:", "${alerta.apalancamiento}")
                    DataRow("ROI EST:", "${alerta.roi}%")
                }
            }
        }
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = Color(0xFF8B949E), fontSize = 13.sp, modifier = Modifier.width(115.dp))
        Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}