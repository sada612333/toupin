package com.lys.toupin.receiver

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lys.toupin.receiver.utils.bodyFontSize
import com.lys.toupin.receiver.utils.horizontalPadding
import com.lys.toupin.receiver.utils.titleFontSize
import com.lys.toupin.receiver.utils.verticalPadding
import com.lys.toupin.receiver.viewmodel.ConnectionState
import com.lys.toupin.receiver.viewmodel.ReceiverViewModel
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    private val viewModel: ReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ReceiverScreen(viewModel)
            }
        }
    }
}

@Composable
fun ReceiverScreen(viewModel: ReceiverViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showStopDialog by viewModel.showSenderStoppedDialog.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background
    ) {
        when (val current = state) {
            is ConnectionState.Connected -> {
                ConnectedScreen(
                    onAttachRenderer = { viewModel.attachRenderer(it) },
                    onDetachRenderer = { viewModel.detachRenderer(it) },
                    onDisconnect = { viewModel.disconnect() },
                    address = current.senderAddress
                )
            }

            else -> {
                val history by viewModel.history.collectAsState()
                InputScreen(
                    state = current,
                    history = history,
                    onConnect = { viewModel.connect(it) },
                    onCancel = { viewModel.disconnect() },
                    onRemoveHistory = { viewModel.removeHistoryItem(it) },
                    onClearHistory = { viewModel.clearHistory() }
                )
            }
        }

        if (showStopDialog) {
            SenderStoppedDialog(onConfirm = { viewModel.dismissSenderStoppedDialog() })
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
    onClearHistory: () -> Unit = {}
) {
    var address by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    val isBusy = state is ConnectionState.Listening || state is ConnectionState.Connecting
    val errorMessage: String? = (state as? ConnectionState.Error)?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .width(72.dp)
                .height(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "投屏接收端",
            fontSize = titleFontSize,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        StatusHint(state, colors.onBackground, colors.primary)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = {
                Text(
                    "请输入 Sender 地址",
                    color = colors.onSurfaceVariant
                )
            },
            placeholder = {
                Text(
                    "192.168.1.100:8888",
                    color = colors.onSurfaceVariant
                )
            },
            supportingText = if (errorMessage != null) {
                {
                    Text(
                        text = errorMessage,
                        color = colors.error,
                        fontSize = 12.sp
                    )
                }
            } else null,
            isError = errorMessage != null,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                disabledTextColor = colors.onSurfaceVariant,
                focusedBorderColor = if (errorMessage != null) colors.error else colors.primary,
                unfocusedBorderColor = if (errorMessage != null) colors.error else colors.outline,
                disabledBorderColor = colors.outline,
                errorBorderColor = colors.error,
                errorCursorColor = colors.error,
                cursorColor = colors.primary
            ),
            textStyle = TextStyle(fontSize = bodyFontSize),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isBusy && address.isNotBlank()) {
                        onConnect(address.trim())
                    }
                }
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isBusy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp),
                    color = Color(0xFFFF9800),
                    strokeWidth = 2.dp
                )

                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                ) {
                    Text(
                        text = "取消",
                        fontSize = bodyFontSize,
                        color = colors.onError
                    )
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text(
                    text = "连接",
                    fontSize = bodyFontSize,
                    color = colors.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "历史连接（点击填入）",
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp
                )
                TextButton(onClick = onClearHistory) {
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item,
                            color = colors.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isBusy) {
                                    address = item
                                }
                                .padding(12.dp)
                        )
                        TextButton(onClick = { onRemoveHistory(item) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "删除",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHint(
    state: ConnectionState,
    defaultColor: Color = Color(0xFFB0B0B0),
    accentColor: Color = Color(0xFF2196F3)
) {
    when (state) {
        is ConnectionState.Idle -> {
            // Idle 时不展示任何文字，提示已由输入框 label 提供
        }

        is ConnectionState.Listening -> {
            val message = if (state.reconnectAttempt > 0) {
                "重连中（第 ${state.reconnectAttempt} 次）..."
            } else {
                "正在连接..."
            }
            Text(
                text = message,
                fontSize = bodyFontSize,
                color = Color(0xFFFF9800)
            )
        }

        is ConnectionState.Connecting -> {
            Text(
                text = state.message,
                fontSize = bodyFontSize,
                color = Color(0xFFFF9800)
            )
        }

        is ConnectionState.Error -> {
            Text(
                text = state.message,
                fontSize = bodyFontSize,
                color = Color(0xFFF44336),
                textAlign = TextAlign.Center
            )
        }

        is ConnectionState.Connected -> {
            Text(
                text = "已连接到 ${state.senderAddress}",
                fontSize = bodyFontSize,
                color = accentColor
            )
        }
    }
}

@Composable
fun ConnectedScreen(
    onAttachRenderer: (SurfaceViewRenderer) -> Unit,
    onDetachRenderer: (SurfaceViewRenderer) -> Unit,
    onDisconnect: () -> Unit,
    address: String
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var controlsVisible by remember { mutableStateOf(true) }
    val renderer = remember { SurfaceViewRenderer(context) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onAttachRenderer(renderer)
        onDispose {
            onDetachRenderer(renderer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable {
                controlsVisible = !controlsVisible
            }
    ) {
        AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "已连接",
                            color = Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = address,
                            color = colors.onSurface,
                            fontSize = 16.sp
                        )
                    }

                    Button(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = colors.onError
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "断开", color = colors.onError)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedPreviewSurface(
    address: String = "ws://192.168.1.100:8888",
    controlsVisible: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 预览时用纯色占位代替 SurfaceViewRenderer 视频画面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
        ) {
            Text(
                text = "[ 视频画面预览占位 ]",
                color = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
                fontSize = 14.sp
            )
        }

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "已连接",
                            color = Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = address,
                            color = colors.onSurface,
                            fontSize = 16.sp
                        )
                    }

                    Button(
                        onClick = {},
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = colors.onError
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "断开", color = colors.onError)
                    }
                }
            }
        }
    }
}

@Composable
fun SenderStoppedDialog(
    onConfirm: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onConfirm,
        icon = {
            Icon(
                imageVector = Icons.Default.SignalWifiOff,
                contentDescription = null,
                tint = colors.error
            )
        },
        title = { Text("发送端已停止投屏", color = colors.onSurface) },
        text = {
            Text(
                "发送端已主动停止投屏，连接已终止。",
                color = colors.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = colors.primary)
            }
        }
    )
}

// ================= Previews =================

@Preview(showBackground = true, name = "Idle 空闲")
@Composable
private fun IdlePreview() {
    MaterialTheme {
        InputScreen(state = ConnectionState.Idle)
    }
}

@Preview(showBackground = true, name = "Listening 连接中")
@Composable
private fun ListeningPreview() {
    MaterialTheme {
        InputScreen(state = ConnectionState.Listening(reconnectAttempt = 0))
    }
}

@Preview(showBackground = true, name = "Listening 重连中（第 3 次）")
@Composable
private fun ReconnectingPreview() {
    MaterialTheme {
        InputScreen(state = ConnectionState.Listening(reconnectAttempt = 3))
    }
}

@Preview(showBackground = true, name = "Connecting 协商中")
@Composable
private fun ConnectingPreview() {
    MaterialTheme {
        InputScreen(state = ConnectionState.Connecting("正在协商..."))
    }
}

@Preview(showBackground = true, name = "Error 错误提示")
@Composable
private fun ErrorPreview() {
    MaterialTheme {
        InputScreen(
            state = ConnectionState.Error("无法连接到该地址，请检查网络或地址")
        )
    }
}

@Preview(showBackground = true, name = "Idle + 历史连接")
@Composable
private fun IdleWithHistoryPreview() {
    MaterialTheme {
        InputScreen(
            state = ConnectionState.Idle,
            history = listOf(
                "ws://192.168.1.100:8888",
                "ws://192.168.1.101:8889",
                "ws://10.0.0.5:8888"
            )
        )
    }
}

@Preview(showBackground = true, name = "Connected 全屏 + 控制浮层")
@Composable
private fun ConnectedWithControlsPreview() {
    MaterialTheme {
        ConnectedPreviewSurface(
            address = "ws://192.168.1.100:8888",
            controlsVisible = true
        )
    }
}

@Preview(showBackground = true, name = "Connected 全屏（浮层已隐藏）")
@Composable
private fun ConnectedNoControlsPreview() {
    MaterialTheme {
        ConnectedPreviewSurface(
            address = "ws://192.168.1.100:8888",
            controlsVisible = false
        )
    }
}

@Preview(showBackground = true, name = "Sender 停止投屏对话框")
@Composable
private fun SenderStoppedDialogPreview() {
    MaterialTheme {
        val colors = MaterialTheme.colorScheme
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            SenderStoppedDialog()
        }
    }
}
