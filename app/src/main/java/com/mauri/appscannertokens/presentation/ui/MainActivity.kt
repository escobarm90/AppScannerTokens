package com.mauri.appscannertokens.presentation.ui

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) LogRepository.add("Permiso de notificaciones denegado.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCriticalPermissions()

        setContent {
            MaterialTheme {
                val monitorViewModel: MonitorViewModel = viewModel()
                MonitorScreen(
                    viewModel = monitorViewModel,
                    onOpenConfig = {
                        startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                    },
                    onExit = {
                        monitorViewModel.setMotorRunning(false)
                        finishAffinity()
                        kotlin.system.exitProcess(0)
                    }
                )
            }
        }
    }

    private fun requestCriticalPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel = viewModel(),
    onOpenConfig: () -> Unit,
    onExit: () -> Unit
) {
    val alerts by viewModel.alerts.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val positions by viewModel.positions.collectAsState()
    val config by viewModel.config.collectAsState()
    val walletBalance by viewModel.balance.collectAsState()
    val isLoadingBalance by viewModel.isLoadingBalance.collectAsState()
    val isMotorRunning by viewModel.isMotorRunning.collectAsState()
    val isDebugEnabled by viewModel.isDebugEnabled.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    val background = Color(0xFF0d1117)
    val card = Color(0xFF161b22)
    val border = Color(0xFF30363d)
    val primary = Color(0xFF58a6ff)
    val tabs = listOf("Oportunidades", "Posiciones", "Consola")

    LaunchedEffect(Unit) {
        viewModel.reloadConfig()
        viewModel.refreshBalance()
    }

    Column(modifier = Modifier.fillMaxSize().background(background).padding(top = 16.dp)) {
        Header(
            primary = primary,
            card = card,
            border = border,
            onOpenConfig = onOpenConfig,
            onExit = onExit
        )

        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = background,
            contentColor = primary,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(pagerState.currentPage),
                    color = primary
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            text = title,
                            color = if (pagerState.currentPage == index) primary else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> OpportunitiesPage(
                    alerts = alerts,
                    config = config,
                    walletBalance = walletBalance,
                    isLoadingBalance = isLoadingBalance,
                    isMotorRunning = isMotorRunning,
                    primary = primary,
                    card = card,
                    border = border,
                    onRefresh = {
                        viewModel.reloadConfig()
                        viewModel.refreshBalance()
                    },
                    onMotorChanged = viewModel::setMotorRunning,
                    onExecuteLimit = viewModel::executeLimit,
                    onExecuteMarket = viewModel::executeMarket,
                    onDismiss = viewModel::removeAlert
                )

                1 -> PositionsPage(
                    positions = positions,
                    card = card,
                    border = border,
                    onClosePosition = viewModel::closePosition,
                    onRemovePosition = viewModel::removePositionFromScreen
                )

                2 -> ConsolePage(
                    logs = logs,
                    isDebugEnabled = isDebugEnabled,
                    card = card,
                    border = border,
                    onDebugChanged = viewModel::setDebugEnabled
                )
            }
        }
    }
}

@Composable
private fun Header(
    primary: Color,
    card: Color,
    border: Color,
    onOpenConfig: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Dashboard", color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFda3633)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Salir", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onOpenConfig,
                colors = ButtonDefaults.buttonColors(containerColor = card),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, border),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Parametros", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OpportunitiesPage(
    alerts: List<AlertData>,
    config: UserConfig,
    walletBalance: Double,
    isLoadingBalance: Boolean,
    isMotorRunning: Boolean,
    primary: Color,
    card: Color,
    border: Color,
    onRefresh: () -> Unit,
    onMotorChanged: (Boolean) -> Unit,
    onExecuteLimit: suspend (AlertExecutionRequest) -> String,
    onExecuteMarket: suspend (AlertExecutionRequest) -> String,
    onDismiss: (AlertData) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        WalletCard(
            balance = walletBalance,
            isLoading = isLoadingBalance,
            onRefresh = onRefresh
        )

        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = card),
            border = androidx.compose.foundation.BorderStroke(1.dp, border)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Motor de Escaneo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (isMotorRunning) "Activo - Analizando Binance" else "Motor detenido",
                        color = if (isMotorRunning) Color(0xFF2ea043) else Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = isMotorRunning,
                    onCheckedChange = onMotorChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF2ea043),
                        checkedTrackColor = Color(0xFF2ea043).copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ULTIMAS OPORTUNIDADES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh) {
                Text("Refrescar Config", color = primary, fontSize = 10.sp)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (alerts.isEmpty()) {
                Text(
                    if (isMotorRunning) "Esperando cruces perfectos..." else "Enciende el motor arriba.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(alerts) { alert ->
                        AlertCard(
                            alerta = alert,
                            config = config,
                            billetera = walletBalance,
                            onExecuteLimit = onExecuteLimit,
                            onExecuteMarket = onExecuteMarket,
                            onDismiss = { onDismiss(alert) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletCard(balance: Double, isLoading: Boolean, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1a2b22)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2ea043))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SALDO DISPONIBLE (USDT)", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isLoading) "Consultando..." else "${'$'}${String.format(Locale.US, "%.2f", balance)}",
                    color = if (isLoading) Color.White else Color(0xFF2ea043),
                    fontSize = if (isLoading) 20.sp else 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            IconButton(onClick = onRefresh) {
                Text("R", fontSize = 20.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun PositionsPage(
    positions: List<ActivePosition>,
    card: Color,
    border: Color,
    onClosePosition: (String) -> Unit,
    onRemovePosition: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "POSICIONES EN HISTORIAL / ACTIVAS (${positions.size})",
            color = Color(0xFFe3b341),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (positions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay posiciones registradas.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(positions) { position ->
                    PositionCard(
                        position = position,
                        card = card,
                        border = border,
                        onClosePosition = onClosePosition,
                        onRemovePosition = onRemovePosition
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionCard(
    position: ActivePosition,
    card: Color,
    border: Color,
    onClosePosition: (String) -> Unit,
    onRemovePosition: (String) -> Unit
) {
    val isProfit = position.pnlNeto >= 0
    val statusColor = if (isProfit) Color(0xFF2ea043) else Color(0xFFda3633)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (position.isClosed) Color(0xFF21262d) else card),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (position.isClosed) Color(0xFF30363d) else statusColor
        )
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${position.symbol} [${position.side}]",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = if (position.isClosed) "ESTADO: CERRADA" else "ESTADO: EN VIVO (MONITOR)",
                        color = if (position.isClosed) Color.Gray else Color(0xFF58a6ff),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(onClick = { onRemovePosition(position.symbol) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Borrar de pantalla",
                        tint = Color(0xFFda3633).copy(alpha = 0.85f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Entrada: ${String.format(Locale.US, "%.5f", position.entryPrice)}",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    if (position.isClosed) {
                        Text(
                            text = "Venta/Cierre: ${String.format(Locale.US, "%.5f", position.currentPrice)}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Actual (Live): ${String.format(Locale.US, "%.5f", position.currentPrice)}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Nivel TS: ${position.trailingLevel}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${if (isProfit) "+" else ""}${String.format(Locale.US, "%.2f%%", position.roePct)}",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.2f", position.pnlNeto)} USDT",
                        color = statusColor,
                        fontSize = 14.sp
                    )
                }
            }

            if (!position.isClosed) {
                Spacer(modifier = Modifier.height(12.dp))

                val totalDistance = abs(position.dynamicTp - position.entryPrice)
                val progress = if (totalDistance > 0) abs(position.currentPrice - position.entryPrice) / totalDistance else 0.0
                LinearProgressIndicator(
                    progress = { progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = statusColor,
                    trackColor = border
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onClosePosition(position.symbol) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFda3633)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "CERRAR POSICION AL MARKET",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsolePage(
    logs: List<String>,
    isDebugEnabled: Boolean,
    card: Color,
    border: Color,
    onDebugChanged: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = card),
            border = androidx.compose.foundation.BorderStroke(1.dp, border)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Modo Debug", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Mostrar calculos en pantalla", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(
                    checked = isDebugEnabled,
                    onCheckedChange = onDebugChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFe3b341),
                        checkedTrackColor = Color(0xFFe3b341).copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("TERMINAL EN VIVO", color = Color(0xFFe3b341), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, border)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), reverseLayout = true) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}