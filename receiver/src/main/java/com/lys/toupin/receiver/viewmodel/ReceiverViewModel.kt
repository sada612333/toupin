package com.lys.toupin.receiver.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lys.toupin.receiver.utils.AddressUtils
import com.lys.toupin.receiver.webrtc.WebRTCManager
import com.lys.toupin.receiver.websocket.WebSocketClient
import com.lys.toupin.receiver.websocket.WebSocketClientListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class ReceiverViewModel(application: Application) : AndroidViewModel(application),
    WebSocketClientListener {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(
        AddressUtils.loadHistory(application)
    )
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _showSenderStoppedDialog = MutableStateFlow(false)
    val showSenderStoppedDialog: StateFlow<Boolean> = _showSenderStoppedDialog.asStateFlow()

    private var webSocketClient: WebSocketClient? = null
    private var webRTCManager: WebRTCManager? = null

    @Volatile
    private var currentAddress: String? = null

    /**
     * 发起连接。address 为用户输入的 `host:port`。
     * 校验失败时会把状态切换为 `ConnectionState.Error`，不会写入历史。
     */
    fun connect(address: String) {
        val result = AddressUtils.validateAndNormalize(address)
        val normalized = result.getOrNull()
        if (normalized == null) {
            _state.value = ConnectionState.Error(
                result.exceptionOrNull()?.message ?: "地址无效"
            )
            return
        }

        // 校验通过才写入历史
        _history.value = AddressUtils.addToHistory(getApplication(), address)
        currentAddress = normalized

        // 先清理旧的客户端，避免状态被泄漏的协程覆盖
        webSocketClient?.disconnect()
        webRTCManager?.close()

        val rtc = WebRTCManager(getApplication()) { type, payload ->
            webSocketClient?.send(type, payload)
        }
        rtc.initialize()
        rtc.createPeerConnection()
        webRTCManager = rtc

        val ws = WebSocketClient(this)
        webSocketClient = ws
        ws.connect(normalized)
    }

    fun disconnect() {
        webSocketClient?.disconnect()
        webRTCManager?.close()
        webSocketClient = null
        webRTCManager = null
        currentAddress = null
        _state.value = ConnectionState.Idle
    }

    fun removeHistoryItem(address: String) {
        _history.value = AddressUtils.removeFromHistory(getApplication(), address)
    }

    fun clearHistory() {
        _history.value = AddressUtils.clearHistory(getApplication())
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        webRTCManager?.attachRenderer(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        webRTCManager?.detachRenderer(renderer)
    }

    fun dismissSenderStoppedDialog() {
        _showSenderStoppedDialog.value = false
    }

    override fun onConnecting(attempt: Int) {
        viewModelScope.launch {
            _state.value = ConnectionState.Listening(attempt)
        }
    }

    override fun onConnected() {
        viewModelScope.launch {
            _state.value = ConnectionState.Connecting("正在协商...")
        }
    }

    override fun onDisconnected() {
        // 由 onReconnecting 或 disconnect() 处理状态
    }

    override fun onReconnecting(attempt: Int) {
        viewModelScope.launch {
            _state.value = ConnectionState.Listening(attempt)
        }
    }

    override fun onConnectionError(message: String) {
        viewModelScope.launch {
            _state.value = ConnectionState.Error(message)
        }
    }

    override fun onMessageReceived(type: String, payload: Map<String, Any>) {
        when (type) {
            "offer" -> {
                val sdp = payload["sdp"] as? String ?: return
                webRTCManager?.handleOffer(sdp)
                viewModelScope.launch {
                    val addr = currentAddress ?: return@launch
                    _state.value = ConnectionState.Connected(addr)
                }
            }

            "ice-candidate" -> {
                val sdpMid = payload["sdpMid"] as? String ?: return
                val sdpMLineIndex = (payload["sdpMLineIndex"] as? Number)?.toInt() ?: return
                val candidate = payload["candidate"] as? String ?: return
                webRTCManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }

            "ice-connected" -> {
                val addr = currentAddress ?: return
                viewModelScope.launch {
                    _state.value = ConnectionState.Connected(addr)
                }
            }

            "ice-failed" -> {
                // 交由重连逻辑处理
            }
        }
    }

    override fun onSenderStopped(reason: String) {
        webRTCManager?.close()
        webSocketClient?.disconnect()
        webSocketClient = null
        webRTCManager = null
        currentAddress = null
        _showSenderStoppedDialog.value = true
        viewModelScope.launch {
            _state.value = ConnectionState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient?.release()
        webRTCManager?.release()
        webSocketClient = null
        webRTCManager = null
    }
}
