package com.lys.toupin

sealed class ScreenShareState {
    object Idle : ScreenShareState()
    object Connecting : ScreenShareState()
    data class Ready(
        val address: String,
        val connectedDevices: List<String>
    ) : ScreenShareState()
    data class Error(val message: String) : ScreenShareState()
}
