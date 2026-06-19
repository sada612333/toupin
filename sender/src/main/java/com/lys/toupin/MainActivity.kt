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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
            ToupinTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val isWifiConnected by collectIsOnWifiState()

                ScreenShareScreen(
                    state = state,
                    isWifiConnected = isWifiConnected,
                    onStart = { viewModel.startScreenShare(this@MainActivity) },
                    onStop = { viewModel.stopScreenShare(this@MainActivity) },
                    onRetry = { viewModel.startScreenShare(this@MainActivity) },
                    onForceStartWithoutWifi = { viewModel.startScreenShare(this@MainActivity) },
                )
            }
        }
    }
}

/**
 * 投屏发送端主界面（无状态）。
 *
 * - 所有颜色通过 [MaterialTheme.colorScheme] 获取
 * - 所有字号通过 [MaterialTheme.typography] 获取
 * - 所有文案通过 [stringResource] 获取
 * - 所有圆角通过 [MaterialTheme.shapes] 获取
 */
@Composable
fun ScreenShareScreen(
    state: ScreenShareState,
    isWifiConnected: Boolean,
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
    onForceStartWithoutWifi: () -> Unit = {},
) {
    var showNoWifiConfirmDialog by remember(state) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {

            Text(
                text = stringResource(R.string.title_screen_cast),
                style = MaterialTheme.typography.headlineMedium,
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
                        isWifiConnected = isWifiConnected,
                        onStart = onStart,
                        onRequestNoWifiDialog = { showNoWifiConfirmDialog = true },
                    )
                }

                is ScreenShareState.Connecting -> {
                    ConnectingContent()
                }

                is ScreenShareState.Ready -> {
                    ReadyContent(
                        address = state.address,
                        connectedDevices = state.connectedDevices,
                        onStop = onStop,
                    )
                }

                is ScreenShareState.Error -> {
                    ErrorContent(
                        message = state.message,
                        isWifiConnected = isWifiConnected,
                        onRetry = onRetry,
                        onRequestNoWifiDialog = { showNoWifiConfirmDialog = true },
                    )
                }
            }
        }
    }

    if (showNoWifiConfirmDialog) {
        NoWifiConfirmDialog(
            onConfirm = {
                showNoWifiConfirmDialog = false
                onForceStartWithoutWifi()
            },
            onDismiss = { showNoWifiConfirmDialog = false },
        )
    }
}

@Composable
private fun collectIsOnWifiState(
    context: Context = LocalContext.current,
): State<Boolean> {
    return produceState(initialValue = isOnWifi(context), key1 = context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                value = true
            }

            override fun onLost(network: Network) {
                value = isOnWifi(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                value = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        cm.registerNetworkCallback(request, callback)

        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }
}

@Composable
private fun NoWifiConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_icon_warning),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = { Text(stringResource(R.string.wifi_dialog_title)) },
        text = { Text(stringResource(R.string.wifi_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.wifi_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.wifi_dialog_cancel))
            }
        },
    )
}

@Composable
private fun WarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_icon_warning),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.wifi_warning),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusIndicator(state: ScreenShareState) {
    val color = when (state) {
        is ScreenShareState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        is ScreenShareState.Connecting -> MaterialTheme.colorScheme.tertiary
        is ScreenShareState.Ready -> MaterialTheme.colorScheme.primary
        is ScreenShareState.Error -> MaterialTheme.colorScheme.error
    }
    val textRes = when (state) {
        is ScreenShareState.Idle -> R.string.status_idle
        is ScreenShareState.Connecting -> R.string.status_connecting
        is ScreenShareState.Ready -> R.string.status_ready
        is ScreenShareState.Error -> R.string.status_error
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IdleContent(
    isWifiConnected: Boolean,
    onStart: () -> Unit,
    onRequestNoWifiDialog: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            if (isWifiConnected) onStart() else onRequestNoWifiDialog()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.start_screen_share),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ConnectingContent() {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.status_connecting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyContent(
    address: String,
    connectedDevices: List<String>,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val displayAddress = address.removePrefix("ws://").removePrefix("wss://")
    val copiedText = stringResource(R.string.copied_to_clipboard)

    AddressCard(address = displayAddress) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("address", displayAddress))
        Toast.makeText(
            context,
            copiedText,
            Toast.LENGTH_SHORT,
        ).show()
    }

    Spacer(modifier = Modifier.height(24.dp))

    if (connectedDevices.isEmpty()) {
        EmptyDevicesContent()
    } else {
        ConnectedDevicesList(devices = connectedDevices)
    }

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedButton(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(
            text = stringResource(R.string.stop_screen_share),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun AddressCard(address: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.address_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.copy_address))
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = stringResource(R.string.cd_icon_devices),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.waiting_device),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectedDevicesList(devices: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.connected_devices),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it }) { device ->
                    DeviceItem(device)
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(deviceId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = deviceId,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    isWifiConnected: Boolean,
    onRetry: () -> Unit,
    onRequestNoWifiDialog: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = stringResource(R.string.cd_icon_error),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            )
            .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = { if (isWifiConnected) onRetry() else onRequestNoWifiDialog() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.retry),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

// ================= Previews =================

@Preview(showBackground = true, name = "Idle")
@Composable
private fun IdleStatePreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Idle,
            isWifiConnected = true,
        )
    }
}

@Preview(showBackground = true, name = "Idle / No Wifi")
@Composable
private fun IdleNoWifiPreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Idle,
            isWifiConnected = false,
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun ConnectingStatePreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Connecting,
            isWifiConnected = true,
        )
    }
}

@Preview(showBackground = true, name = "Ready / No Devices")
@Composable
private fun ReadyNoDevicesPreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Ready(
                address = "192.168.1.100:8080",
                connectedDevices = emptyList(),
            ),
            isWifiConnected = true,
        )
    }
}

@Preview(showBackground = true, name = "Ready / With Devices")
@Composable
private fun ReadyWithDevicesPreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Ready(
                address = "192.168.1.100:8080",
                connectedDevices = listOf("device-123", "device-456", "device-789"),
            ),
            isWifiConnected = true,
        )
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun ErrorStatePreview() {
    ToupinTheme {
        ScreenShareScreen(
            state = ScreenShareState.Error("权限被拒绝"),
            isWifiConnected = false,
        )
    }
}
