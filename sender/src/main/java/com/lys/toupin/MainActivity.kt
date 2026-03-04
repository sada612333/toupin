package com.lys.toupin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel


class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. 声明屏幕投影权限请求器
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 用户已授权屏幕投影，现在可以安全启动服务
            Log.d(TAG, "Screen capture permission granted")
            // 获取 ViewModel 并启动共享
            val viewModel = androidx.lifecycle.ViewModelProvider(this).get(ScreenShareViewModel::class.java)
            viewModel.startSharing(this)
        } else {
            Log.e(TAG, "Screen capture permission denied")
            Toast.makeText(this, "未获得投屏授权", Toast.LENGTH_SHORT).show()
        }
    }


    // 2. 发起屏幕投影请求
    fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // 3. 启动服务的逻辑已移至 ViewModel 中

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "FOREGROUND_SERVICE_MEDIA_PROJECTION permission granted")
            // 权限授予后，尝试发起投屏请求
            requestScreenCapture()
        } else {
            Log.e(TAG, "FOREGROUND_SERVICE_MEDIA_PROJECTION permission denied")
            Toast.makeText(this, "需要前台服务权限才能开始屏幕共享", Toast.LENGTH_LONG).show()
        }
    }

    fun checkForegroundServicePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgPermission = Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            if (ContextCompat.checkSelfPermission(this, fgPermission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgPermission = Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            if (ContextCompat.checkSelfPermission(this, fgPermission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(fgPermission)
                return
            }
        }
        // 如果已经有权限，直接发起投屏请求
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToupinApp()
        }
    }
}

@Composable
fun ToupinApp() {
    val viewModel: ScreenShareViewModel = viewModel()
    val state = viewModel.state.collectAsState()
    val context = LocalContext.current

    val statusMessage = when (state.value) {
        is ScreenShareState.Idle -> "就绪"
        is ScreenShareState.Connecting -> "正在连接..."
        is ScreenShareState.Connected -> "已连接"
        is ScreenShareState.Error -> (state.value as ScreenShareState.Error).message
    }

    val buttonText = when (state.value) {
        is ScreenShareState.Idle, is ScreenShareState.Error -> "开始共享"
        is ScreenShareState.Connecting, is ScreenShareState.Connected -> "停止共享"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "屏幕共享",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    when (state.value) {
                        is ScreenShareState.Idle, is ScreenShareState.Error -> {
                            if (state.value is ScreenShareState.Error) {
                                viewModel.resetError()
                            }
                            val activity = context as? MainActivity
                            if (activity?.checkForegroundServicePermission() == true) {
                                activity.requestScreenCapture()
                            } else {
                                activity?.requestAllPermissions()
                            }
                        }
                        is ScreenShareState.Connecting, is ScreenShareState.Connected -> {
                            viewModel.stopSharing(context)
                        }
                    }
                }
            ) {
                Text(text = buttonText)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ToupinAppPreview() {
    ToupinApp()
}