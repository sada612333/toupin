package com.lys.toupin.receiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lys.toupin.receiver.viewmodel.ReceiverViewModel
import com.lys.toupin.receiver.viewmodel.ConnectionState
import com.lys.toupin.receiver.ui.VideoView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReceiverScreen()
                }
            }
        }
    }
}

@Composable
fun ReceiverScreen(
    viewModel: ReceiverViewModel = viewModel()
) {
    val connectionStatus = viewModel.connectionStatus.collectAsState()
    val signalingStatus = viewModel.signalingStatus.collectAsState()
    val state = viewModel.state.collectAsState()
    val videoTrack = viewModel.videoTrack.collectAsState()
    val isVideoVisible = viewModel.isVideoVisible.collectAsState()
    var ipAddress by remember { mutableStateOf("192.168.10.13") }
    var port by remember { mutableStateOf("8080") }

    // 初始化WebRTC
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(key1 = true) {
        viewModel.initializeWebRTC(context)
        onDispose { }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = if (isVideoVisible.value) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 视频显示区域
        if (isVideoVisible.value) {
            VideoView(
                videoTrack = videoTrack.value,
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }

        // 状态信息区域
        Column(
            modifier = Modifier
                .weight(0.3f)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.connection_status, connectionStatus.value),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = signalingStatus.value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

        // 根据状态决定是否显示输入框
        when (state.value) {
            is ConnectionState.Idle -> {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("发送端 IP 地址") },
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
            else -> {
                // 连接过程中不允许修改输入
                Text(
                    text = "IP: $ipAddress",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "端口: $port",
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
        }

        Button(
            onClick = {
                when (state.value) {
                    is ConnectionState.Idle -> {
                        viewModel.startListening(ipAddress, port.toIntOrNull() ?: 8080)
                    }
                    else -> {
                        viewModel.stopListening()
                    }
                }
            },
            modifier = Modifier
        ) {
            Text(
                text = when (state.value) {
                    is ConnectionState.Idle -> stringResource(R.string.start_listening)
                    else -> stringResource(R.string.stop_listening)
                }
            )
        }
        }
    }
}
