package com.lys.toupin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ScreenShareState {
    object Idle : ScreenShareState()
    object Connecting : ScreenShareState()
    object Connected : ScreenShareState()
    data class Error(val message: String) : ScreenShareState()
}

class ScreenShareViewModel : ViewModel() {
    companion object {
        private const val TAG = "ScreenShareViewModel"
    }

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    val state: StateFlow<ScreenShareState> = _state

    fun startSharing(context: Context, resultCode: Int, data: Intent?) {
        Log.d(TAG, "startSharing triggered state change")
        Log.d(TAG, "Result code: $resultCode")
        Log.d(TAG, "Data: $data")
        
        _state.value = ScreenShareState.Connecting
        // 启动前台服务
        try {
            val intent = Intent(context, ScreenShareService::class.java)
            intent.putExtra("resultCode", resultCode)
            intent.putExtra("data", data)
            Log.d(TAG, "Starting foreground service with intent: $intent")
            context.startForegroundService(intent)
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}")
            _state.value = ScreenShareState.Error("启动服务失败: ${e.message}")
            return
        }
        simulateConnection()
    }

    fun stopSharing(context: Context) {
        Log.d(TAG, "stopSharing called")
        // 停止前台服务
        try {
            val intent = Intent(context, ScreenShareService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }
        _state.value = ScreenShareState.Idle
        Log.d(TAG, "State changed to: Idle")
    }

    fun resetError() {
        Log.d(TAG, "resetError called")
        _state.value = ScreenShareState.Idle
        Log.d(TAG, "State changed to: Idle")
    }

    private fun simulateConnection() {
        Log.d(TAG, "simulateConnection called")
        // 在实际应用中，这里会执行真实的连接逻辑
        // 模拟连接成功
        _state.value = ScreenShareState.Connected
        Log.d(TAG, "State changed to: Connected")
    }

    fun simulateError() {
        Log.d(TAG, "simulateError called")
        _state.value = ScreenShareState.Error("连接失败，请重试")
        Log.d(TAG, "State changed to: Error")
    }
}
