package com.lys.toupin.receiver

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lys.toupin.receiver.viewmodel.ConnectionState
import com.lys.toupin.receiver.viewmodel.ReceiverViewModel
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    private val viewModel: ReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReceiverTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val showStopDialog by viewModel.showSenderStoppedDialog.collectAsStateWithLifecycle()
                val history by viewModel.history.collectAsStateWithLifecycle()

                ReceiverScreen(
                    state = state,
                    history = history,
                    onConnect = { viewModel.connect(it) },
                    onCancel = { viewModel.disconnect() },
                    onRemoveHistory = { viewModel.removeHistoryItem(it) },
                    onClearHistory = { viewModel.clearHistory() },
                    onAttachRenderer = { viewModel.attachRenderer(it) },
                    onDetachRenderer = { viewModel.detachRenderer(it) },
                    onDisconnect = { viewModel.disconnect() },
                    onConfirmSenderStopped = { viewModel.dismissSenderStoppedDialog() },
                    showSenderStoppedDialog = showStopDialog,
                )
            }
        }
    }
}

/**
 * 投屏接收端主界面（无状态）。
 */
@Composable
fun ReceiverScreen(
    state: ConnectionState,
    history: List<String> = emptyList(),
    onConnect: (address: String) -> Unit = {},
    onCancel: () -> Unit = {},
    onRemoveHistory: (address: String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onAttachRenderer: (SurfaceViewRenderer) -> Unit = {},
    onDetachRenderer: (SurfaceViewRenderer) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onConfirmSenderStopped: () -> Unit = {},
    showSenderStoppedDialog: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            is ConnectionState.Connected -> {
                ConnectedScreen(
                    address = state.senderAddress,
                    onAttachRenderer = onAttachRenderer,
                    onDetachRenderer = onDetachRenderer,
                    onDisconnect = onDisconnect,
                )
            }

            else -> {
                InputScreen(
                    state = state,
                    history = history,
                    onConnect = onConnect,
                    onCancel = onCancel,
                    onRemoveHistory = onRemoveHistory,
                    onClearHistory = onClearHistory,
                )
            }
        }

        if (showSenderStoppedDialog) {
            SenderStoppedDialog(onConfirm = onConfirmSenderStopped)
        }
    }
}

@Composable
fun InputScreen(
    state: ConnectionState,
    history: List<String> = emptyList(),
    onConnect: (address: String) -> Unit = {},
    onCancel: () -> Unit = {},
    onRemoveHistory: (address: String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    var address by remember(state) { mutableStateOf("") }
    val isBusy = state is ConnectionState.Listening || state is ConnectionState.Connecting
    val errorMessage: String? = (state as? ConnectionState.Error)?.message
    val dimens = LocalAdaptiveDimens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = dimens.horizontalPadding,
                vertical = dimens.verticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.SignalWifiOff,
            contentDescription = stringResource(R.string.cd_icon_video),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.title_receiver),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        StatusHint(state = state)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.input_address)) },
            placeholder = { Text(stringResource(R.string.address_hint)) },
            supportingText = if (errorMessage != null) {
                { Text(text = errorMessage) }
            } else null,
            isError = errorMessage != null,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                errorTextColor = MaterialTheme.colorScheme.error,
                errorCursorColor = MaterialTheme.colorScheme.error,
                errorBorderColor = MaterialTheme.colorScheme.error,
                errorLabelColor = MaterialTheme.colorScheme.error,
                errorSupportingTextColor = MaterialTheme.colorScheme.error,
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isBusy && address.isNotBlank()) {
                        onConnect(address.trim())
                    }
                },
            ),
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isBusy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    strokeWidth = 2.dp,
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else {
            Button(
                onClick = {
                    if (address.isNotBlank()) onConnect(address.trim())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.connect))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.connection_history_tip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClearHistory) {
                    Text(
                        text = stringResource(R.string.clear_history),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { it }) { item ->
                    HistoryItem(
                        item = item,
                        enabled = !isBusy,
                        onClick = { address = item },
                        onRemove = { onRemoveHistory(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    item: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(12.dp),
        )
        TextButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_icon_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatusHint(state: ConnectionState) {
    val (color, text) = when (state) {
        is ConnectionState.Idle -> {
            Pair(MaterialTheme.colorScheme.onSurfaceVariant, null)
        }

        is ConnectionState.Listening -> {
            val msg = stringResource(
                R.string.reconnecting,
                state.reconnectAttempt,
            )
            Pair(MaterialTheme.colorScheme.tertiary, msg)
        }

        is ConnectionState.Connecting -> {
            Pair(MaterialTheme.colorScheme.tertiary, stringResource(R.string.status_connecting))
        }

        is ConnectionState.Error -> {
            Pair(MaterialTheme.colorScheme.error, state.message)
        }

        is ConnectionState.Connected -> {
            val msg = stringResource(R.string.connected_to, state.senderAddress)
            Pair(MaterialTheme.colorScheme.primary, msg)
        }
    }

    if (text != null) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ConnectedScreen(
    onAttachRenderer: (SurfaceViewRenderer) -> Unit,
    onDetachRenderer: (SurfaceViewRenderer) -> Unit,
    onDisconnect: () -> Unit,
    address: String,
) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    var controlsVisible by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        onAttachRenderer(renderer)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onDetachRenderer(renderer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable { controlsVisible = !controlsVisible },
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.status_connected),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = address,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_icon_close),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.disconnect))
                    }
                }
            }
        }
    }
}

@Composable
fun SenderStoppedDialog(
    onConfirm: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onConfirm,
        icon = {
            Icon(
                imageVector = Icons.Default.SignalWifiOff,
                contentDescription = stringResource(R.string.cd_icon_wifi_off),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.sender_stopped)) },
        text = { Text(stringResource(R.string.sender_stopped_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

// ================= Previews =================

@Preview(showBackground = true, name = "Idle")
@Composable
private fun IdlePreview() {
    ReceiverTheme {
        ReceiverScreen(state = ConnectionState.Idle)
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun ConnectingPreview() {
    ReceiverTheme {
        ReceiverScreen(state = ConnectionState.Connecting(stringResource(R.string.status_connecting)))
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun ErrorPreview() {
    ReceiverTheme {
        ReceiverScreen(
            state = ConnectionState.Error(
                stringResource(R.string.error_connect_failed),
            ),
        )
    }
}

@Preview(showBackground = true, name = "History")
@Composable
private fun HistoryPreview() {
    ReceiverTheme {
        ReceiverScreen(
            state = ConnectionState.Idle,
            history = listOf(
                "ws://192.168.1.100:8888",
                "ws://192.168.1.101:8889",
                "ws://10.0.0.5:8888",
            ),
        )
    }
}

@Preview(showBackground = true, name = "Sender stopped")
@Composable
private fun SenderStoppedDialogPreview() {
    ReceiverTheme {
        val show by remember { mutableStateOf(true) }
        Surface(Modifier.fillMaxSize()) {
            if (show) SenderStoppedDialog {}
        }
    }
}
