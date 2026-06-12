package com.lys.toupin.receiver.viewmodel

sealed class ConnectionState {
    data object Idle : ConnectionState()

    data class Listening(val reconnectAttempt: Int = 0) : ConnectionState()

    data class Connecting(val message: String = "正在协商...") : ConnectionState()

    data class Connected(val senderAddress: String) : ConnectionState()

    data class Error(val message: String) : ConnectionState()
}
