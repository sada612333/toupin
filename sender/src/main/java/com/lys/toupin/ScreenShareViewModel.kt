package com.lys.toupin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenShareViewModel : ViewModel(), ScreenShareService.ServiceListener {

    companion object {
        private const val TAG = "ScreenShareViewModel"
    }

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>? = null

    init {
        // 立即把自己设为 Service 的 listener。注意：在 Activity 生命周期中
        // attachListener 是幂等的；每次 Activity 创建都会覆盖一次。
        ScreenShareService.attachListener(this)
        Log.i(TAG, "init: listener attached")
    }

    fun setMediaProjectionLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.mediaProjectionLauncher = launcher
    }

    fun startScreenShare(context: Activity) {
        Log.i(TAG, "startScreenShare")
        val mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val launcher = mediaProjectionLauncher
        if (launcher == null) {
            Log.e(TAG, "startScreenShare: mediaProjectionLauncher is null")
            _state.value = ScreenShareState.Error("启动器未就绪")
            return
        }
        launcher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    fun onMediaProjectionResult(context: Context, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onMediaProjectionResult resultCode=$resultCode, data?=${data != null}")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            Log.w(TAG, "onMediaProjectionResult: permission denied")
            _state.value = ScreenShareState.Error("权限被拒绝")
        }
    }

    fun stopScreenShare(context: Context) {
        Log.i(TAG, "stopScreenShare")
        val intent = Intent(context, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun onStateChanged(state: ScreenShareState) {
        Log.d(TAG, "onStateChanged → $state")
        viewModelScope.launch {
            _state.value = state
        }
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: detaching listener")
        ScreenShareService.detachListener(this)
        super.onCleared()
    }
}
