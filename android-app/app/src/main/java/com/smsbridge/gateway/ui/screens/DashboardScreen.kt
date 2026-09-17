package com.smsbridge.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smsbridge.gateway.data.GatewayPreferences
import com.smsbridge.gateway.server.SmsHttpServer
import com.smsbridge.gateway.service.GatewayForegroundService
import com.smsbridge.gateway.telephony.SystemMetrics
import com.smsbridge.gateway.ui.theme.*
import com.smsbridge.gateway.ui.util.QrCodeGenerator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    context: Context = LocalContext.current
) {
    val isRunning by GatewayForegroundService.isServiceRunning.collectAsState()
    val prefs = remember { GatewayPreferences(context) }
    val colors = AppTheme.colors

    var ipAddress by remember { mutableStateOf(SystemMetrics.getLocalIpAddress()) }
    var wifiInfo by remember { mutableStateOf(SystemMetrics.getWifiInfo(context)) }
    var batteryInfo by remember { mutableStateOf(SystemMetrics.getBatteryInfo(context)) }
    var showQrDialog by remember { mutableStateOf(false) }

    val logs by SmsHttpServer.logsFlow.collectAsState()
    val sentCount by GatewayPreferences.sentCountFlow.collectAsState()
    val configTrigger by GatewayPreferences.configUpdateFlow.collectAsState()

    // Periodic telemetry refresher
    LaunchedEffect(Unit) {
        while (true) {
            ipAddress = SystemMetrics.getLocalIpAddress()
            wifiInfo = SystemMetrics.getWifiInfo(context)
            batteryInfo = SystemMetrics.getBatteryInfo(context)
            delay(3000)
        }
    }

    val serverUrl = remember(ipAddress, prefs.port, configTrigger) { "http://$ipAddress:${prefs.port}" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Server Control Hero Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isRunning) PrimaryIndigo.copy(alpha = 0.5f) else colors.border,
                        RoundedCornerShape(20.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) StatusSuccess else StatusDanger)
                            )
                            Text(
                                text = if (isRunning) "GATEWAY ACTIVE" else "GATEWAY STOPPED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isRunning) StatusSuccess else StatusDanger
                            )
                        }

                        Switch(
                            checked = isRunning,
                            onCheckedChange = { start ->
                                try {
                                    if (start) {
                                        GatewayForegroundService.start(context)
                                    } else {
                                        GatewayForegroundService.stop(context)
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryIndigo
                            )
                        )
                    }

                    // URL Box
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (colors.isDark) Color.Black.copy(alpha = 0.35f) else Color(0xFFF1F5F9),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "REST API Endpoint",
                                    fontSize = 11.sp,
                                    color = colors.textMuted
                                )
                                Text(
                                    text = serverUrl,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = PrimaryIndigo
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("SMS Bridge URL", serverUrl))
                                        Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy URL",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(onClick = { showQrDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode,
                                        contentDescription = "Show QR",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Telemetry Grid (3 Cards: Battery, Wi-Fi, Sent Stats)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Battery Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                            Text("Battery", fontSize = 11.sp, color = colors.textMuted)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${batteryInfo.level}%" + if (batteryInfo.isCharging) " ⚡" else "",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                }

                // Wi-Fi Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                            Text("Wi-Fi", fontSize = 11.sp, color = colors.textMuted)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = wifiInfo.ssid,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1
                        )
                    }
                }

                // Sent Stats Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = PrimaryIndigoLight, modifier = Modifier.size(16.dp))
                            Text("Sent", fontSize = 11.sp, color = colors.textMuted)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$sentCount",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }

        // Live Dispatched Logs Section Header (Cellular radios removed to give space directly to logs!)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Live Activity Stream",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PrimaryIndigo.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${logs.size} msgs",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryIndigo,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (logs.isNotEmpty()) {
                    TextButton(onClick = { SmsHttpServer.clearLogs() }) {
                        Text("Clear", color = colors.textMuted, fontSize = 12.sp)
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "No messages dispatched yet. Send an SMS from your laptop dashboard to see live transmission logs.",
                                fontSize = 13.sp,
                                color = colors.textMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            items(logs) { log ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = log.recipient,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "SIM ${log.simSlot + 1}",
                                    fontSize = 11.sp,
                                    color = PrimaryIndigoLight
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = log.message,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                maxLines = 1
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            val (badgeBg, badgeTextColor, label) = when (log.status) {
                                "DELIVERED" -> Triple(StatusSuccess.copy(alpha = 0.18f), StatusSuccess, "DELIVERED")
                                "SENT" -> Triple(PrimaryIndigo.copy(alpha = 0.20f), PrimaryIndigo, "SENT")
                                "NOT_SENT", "FAILED" -> Triple(StatusDanger.copy(alpha = 0.18f), StatusDanger, "NOT SENT")
                                else -> Triple(StatusWarning.copy(alpha = 0.18f), StatusWarning, log.status)
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeBg
                            ) {
                                Text(
                                    text = label,
                                    color = badgeTextColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (!log.error.isNullOrBlank() && (log.status == "NOT_SENT" || log.status == "FAILED")) {
                                Text(
                                    text = log.error,
                                    fontSize = 10.sp,
                                    color = StatusDanger,
                                    maxLines = 1
                                )
                            }
                            Text(text = timeStr, fontSize = 10.sp, color = colors.textMuted)
                        }
                    }
                }
            }
        }
    }

    // QR Code Pairing Dialog
    if (showQrDialog) {
        val qrBitmap = remember(serverUrl) { QrCodeGenerator.generateQrBitmap(serverUrl) }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close", color = PrimaryIndigoLight)
                }
            },
            title = { Text("Pairing QR Code", color = colors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Server URL QR Code",
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Text(
                        text = "Scan with your laptop or phone camera to copy the endpoint URL: $serverUrl",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            },
            containerColor = colors.surface
        )
    }
}
