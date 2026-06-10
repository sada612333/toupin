package com.lys.toupin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenShareViewModel : ViewModel(), ScreenShareService.ServiceListener {

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>? = null

    init {
        ScreenShareService.setListener(this)
    }

    fun setMediaProjectionLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.mediaProjectionLauncher = launcher
    }

    fun startScreenShare(context: Activity) {
        val mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher?.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    fun onMediaProjectionResult(context: Context, resultCode: Int, data: Intent?) {
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
            _state.value = ScreenShareState.Error("权限被拒绝")
        }
    }

    fun stopScreenShare(context: Context) {
        val intent = Intent(context, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        context.startService(intent)
    }

    override fun onStateChanged(state: ScreenShareState) {
        viewModelScope.launch {
            _state.value = state
        }
    }
}
