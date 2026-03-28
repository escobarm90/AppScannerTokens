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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = alerta.token,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = alerta.hora,
                    color = Color(0xFF8B949E),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // FILA 2: SEÑAL Y PNL ESTIMADO
            Text(
                text = "${if (isLong) "🟢" else "🔴"} ${alerta.senal}  |  PNL: $${alerta.pnlNeto} USDT",
                color = mainColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 12.dp))

            // FILA 3: CUADRÍCULA DE DATOS (Uno abajo del otro)
            Row(modifier = Modifier.fillMaxWidth()) {
                // Columna Izquierda
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DataItem("PRECIO ENTRADA", alerta.precio)
                    DataItem("TAKE PROFIT", alerta.tp, Color(0xFF2EA043))
                    DataItem("STOP LOSS", alerta.sl, Color(0xFFF85149))
                }

                // Columna Derecha
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DataItem("MARGEN", "$${alerta.margen} USDT")
                    DataItem("APALANCAMIENTO", alerta.apalancamiento)
                    DataItem("ROI ESTIMADO", "${alerta.roi}%", mainColor)
                }
            }
        }
    }
}

// Nuevo componente diseñado para apilar el título y el valor verticalmente
@Composable
fun DataItem(label: String, value: String, valueColor: Color = Color.White) {
    Column {
        Text(
            text = label,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value.ifEmpty { "Calculando..." },
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}