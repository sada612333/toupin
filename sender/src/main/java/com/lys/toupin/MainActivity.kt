package com.lys.toupin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: ScreenShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            viewModel.onMediaProjectionResult(
                this,
                result.resultCode,
                result.data
            )
        }

        viewModel.setMediaProjectionLauncher(mediaProjectionLauncher)

        setContent {
            MaterialTheme {
                ScreenShareScreen(viewModel)
            }
        }
    }
}

@Composable
fun ScreenShareScreen(viewModel: ScreenShareViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isWifiConnected by collectIsOnWifiState(context)
    var showNoWifiConfirmDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "投屏发送端",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!isWifiConnected) {
                WarningBanner()
            }

            StatusIndicator(state)

            Spacer(modifier = Modifier.height(24.dp))

            when (state) {
                is ScreenShareState.Idle -> {
                    IdleContent(
                        onStart = {
                            if (isWifiConnected) {
                                viewModel.startScreenShare(context as ComponentActivity)
                            } else {
                                showNoWifiConfirmDialog = true
                            }
                        }
                    )
                }
                is ScreenShareState.Connecting -> {
                    ConnectingContent()
                }
                is ScreenShareState.Ready -> {
                    val readyState = state as ScreenShareState.Ready
                    ReadyContent(
                        address = readyState.address,
                        connectedDevices = readyState.connectedDevices,
                        onStop = { viewModel.stopScreenShare(context) }
                    )
                }
                is ScreenShareState.Error -> {
                    val errorState = state as ScreenShareState.Error
                    ErrorContent(
                        message = errorState.message,
                        onRetry = {
                            if (isWifiConnected) {
                                viewModel.startScreenShare(context as ComponentActivity)
                            } else {
                                showNoWifiConfirmDialog = true
                            }
                        }
                    )
                }
            }
        }
    }

    if (showNoWifiConfirmDialog) {
        NoWifiConfirmDialog(
            onConfirm = {
                showNoWifiConfirmDialog = false
                viewModel.startScreenShare(context as ComponentActivity)
            },
            onDismiss = {
                showNoWifiConfirmDialog = false
            }
        )
    }
}

@Composable
fun collectIsOnWifiState(context: Context): State<Boolean> {
    return produceState(initialValue = isOnWifi(context)) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                value = true
            }

            override fun onLost(network: Network) {
                value = isOnWifi(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                value = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        awaitDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }
}

@Composable
fun NoWifiConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = Color.White,
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(text = "未连接 WiFi")
        },
        text = {
            Text(
                text = "当前未连接 WiFi 网络，投屏效果可能不稳定（需要在同一局域网内才能接收）。\n\n是否仍要继续？"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("继续投屏", color = Color(0xFFFF9800))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    )
}

@Composable
fun WarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "请连接WiFi网络",
                color = Color(0xFFF57C00)
            )
        }
    }
}

@Composable
fun StatusIndicator(state: ScreenShareState) {
    val (color, text) = when (state) {
        is ScreenShareState.Idle -> Color(0xFF9E9E9E) to "待投屏"
        is ScreenShareState.Connecting -> Color(0xFFFF9800) to "正在启动..."
        is ScreenShareState.Ready -> Color(0xFF4CAF50) to "投屏就绪"
        is ScreenShareState.Error -> Color(0xFFF44336) to "错误"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(50))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, fontSize = 16.sp)
    }
}

@Composable
fun ColumnScope.IdleContent(onStart: () -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = "开始投屏", fontSize = 18.sp)
    }
}

@Composable
fun ColumnScope.ConnectingContent() {
    Spacer(modifier = Modifier.weight(1f))
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "正在启动投屏服务...")
    Spacer(modifier = Modifier.weight(1f))
}

@Composable
fun ColumnScope.ReadyContent(
    address: String,
    connectedDevices: List<String>,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    AddressCard(address = address) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    Spacer(modifier = Modifier.height(24.dp))

    if (connectedDevices.isEmpty()) {
        EmptyDevicesContent()
    } else {
        ConnectedDevicesList(devices = connectedDevices)
    }

    Spacer(modifier = Modifier.weight(1f))

    Button(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = "停止投屏", fontSize = 18.sp)
    }
}

@Composable
fun AddressCard(address: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "投屏地址",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCopy) {
                    Text("复制")
                }
            }
        }
    }
}

@Composable
fun EmptyDevicesContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            tint = Color(0xFF9E9E9E),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "等待设备连接...",
            color = Color(0xFF757575)
        )
    }
}

@Composable
fun ConnectedDevicesList(devices: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "已连接设备",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceItem(device)
                }
            }
        }
    }
}

@Composable
fun DeviceItem(deviceId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color(0xFF4CAF50), RoundedCornerShape(50))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = deviceId,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ColumnScope.ErrorContent(message: String, onRetry: () -> Unit) {
    Spacer(modifier = Modifier.weight(1f))
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = null,
        tint = Color(0xFFF44336),
        modifier = Modifier.size(48.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = message,
        color = Color(0xFFF44336),
        fontSize = 16.sp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = "重试", fontSize = 18.sp)
    }
    Spacer(modifier = Modifier.weight(1f))
}

fun isOnWifi(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

@Preview(showBackground = true, name = "Idle State")
@Composable
fun IdleStatePreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Idle,
            isWifiConnected = true
        )
    }
}

@Preview(showBackground = true, name = "Connecting State")
@Composable
fun ConnectingStatePreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Connecting,
            isWifiConnected = true
        )
    }
}

@Preview(showBackground = true, name = "Ready State - No Devices")
@Composable
fun ReadyStateNoDevicesPreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Ready(
                address = "ws://192.168.1.100:8080",
                connectedDevices = emptyList()
            ),
            isWifiConnected = true
        )
    }
}

@Preview(showBackground = true, name = "Ready State - With Devices")
@Composable
fun ReadyStateWithDevicesPreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Ready(
                address = "ws://192.168.1.100:8080",
                connectedDevices = listOf("device-123", "device-456", "device-789")
            ),
            isWifiConnected = true
        )
    }
}

@Preview(showBackground = true, name = "Error State")
@Composable
fun ErrorStatePreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Error("网络连接失败，请检查WiFi"),
            isWifiConnected = false
        )
    }
}

@Preview(showBackground = true, name = "No WiFi Warning")
@Composable
fun NoWifiWarningPreview() {
    MaterialTheme {
        ScreenShareScreenPreview(
            state = ScreenShareState.Idle,
            isWifiConnected = false
        )
    }
}

@Composable
fun ScreenShareScreenPreview(
    state: ScreenShareState,
    isWifiConnected: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "投屏发送端",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!isWifiConnected) {
                WarningBanner()
            }

            StatusIndicator(state)

            Spacer(modifier = Modifier.height(24.dp))

            when (state) {
                is ScreenShareState.Idle -> {
                    IdleContent(onStart = {})
                }
                is ScreenShareState.Connecting -> {
                    ConnectingContent()
                }
                is ScreenShareState.Ready -> {
                    ReadyContent(
                        address = state.address,
                        connectedDevices = state.connectedDevices,
                        onStop = {}
                    )
                }
                is ScreenShareState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = {}
                    )
                }
            }
        }
    }
}
