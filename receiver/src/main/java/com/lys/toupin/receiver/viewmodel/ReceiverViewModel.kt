package com.lys.toupin.receiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.lys.toupin.receiver.websocket.WebSocketClient
import com.lys.toupin.receiver.webrtc.WebRTCManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import java.util.*
import android.util.Log

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Listening : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
}

class ReceiverViewModel : ViewModel() {
    private val TAG = "ReceiverViewModel"
    
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _connectionStatus = MutableStateFlow("未连接")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _signalingStatus = MutableStateFlow("信令未连接")
    val signalingStatus: StateFlow<String> = _signalingStatus

    private var webSocketClient: WebSocketClient? = null
    private var webRTCManager: WebRTCManager? = null
    private val clientId = "receiver-${UUID.randomUUID().toString().substring(0, 8)}"
    
    // 添加视频轨道状态
    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    val videoTrack: StateFlow<VideoTrack?> = _videoTrack
    
    private val _isVideoVisible = MutableStateFlow(false)
    val isVideoVisible: StateFlow<Boolean> = _isVideoVisible

    fun initializeWebRTC(context: Context) {
        if (webRTCManager == null) {
            webRTCManager = WebRTCManager(context)
            webRTCManager?.setListener(object : WebRTCManager.WebRTCListener {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    // 通过WebSocket发送ICE候选
                    webSocketClient?.sendIceCandidate(
                        iceCandidate.sdp, 
                        iceCandidate.sdpMid ?: "0", 
                        iceCandidate.sdpMLineIndex
                    )
                }
                
                override fun onLocalDescription(description: SessionDescription) {
                    // 发送Answer到信令服务器
                    if (description.type == SessionDescription.Type.ANSWER) {
                        webSocketClient?.sendAnswer(description.description)
                    }
                }
                
                override fun onVideoStreamReceived(track: VideoTrack) {
                    Log.i(TAG, "🎬 收到视频流，开始播放")
                    _videoTrack.value = track
                    _isVideoVisible.value = true
                    _state.value = ConnectionState.Connected
                    _connectionStatus.value = "视频流已连接"
                }
                
                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    Log.i(TAG, "🔗 WebRTC连接状态: $state")
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            _state.value = ConnectionState.Connected
                            _connectionStatus.value = "WebRTC已连接"
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _state.value = ConnectionState.Idle
                            _connectionStatus.value = "WebRTC连接失败"
                        }
                        else -> {}
                    }
                }
                
                override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                    Log.i(TAG, "🧊 ICE连接状态: $state")
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "❌ WebRTC错误: $error")
                    _signalingStatus.value = "WebRTC错误: $error"
                }
            })
            
            webRTCManager?.initialize()
        }
    }
    
    fun startListening(ipAddress: String, port: Int) {
        viewModelScope.launch {
            _state.value = ConnectionState.Listening
            _connectionStatus.value = "监听中..."
            _signalingStatus.value = "正在连接信令服务器..."

            // 连接到WebSocket信令服务器
            val serverUrl = "ws://$ipAddress:$port"
            Log.i(TAG, "🚀 启动信令连接: $serverUrl")
            
            // 初始化WebRTC（如果还未初始化）
            if (webRTCManager == null) {
                Log.w(TAG, "⚠️ WebRTC未初始化，跳过真实连接")
                _signalingStatus.value = "WebRTC未初始化，无法建立真实连接"
                return@launch
            }
            
            webSocketClient = WebSocketClient(serverUrl, clientId)
            webSocketClient?.setListener(object : WebSocketClient.WebSocketListener {
                override fun onConnected() {
                    Log.i(TAG, "✅ 信令连接成功")
                    _signalingStatus.value = "信令已连接"
                    _state.value = ConnectionState.Connecting
                    _connectionStatus.value = "连接中..."
                    
                    // 创建WebRTC peer connection
                    webRTCManager?.createPeerConnection()
                    Log.i(TAG, "🔗 WebRTC连接建立中...")
                }

                override fun onDisconnected() {
                    Log.w(TAG, "⚠️ 信令连接断开")
                    _signalingStatus.value = "信令已断开"
                    if (_state.value != ConnectionState.Idle) {
                        _state.value = ConnectionState.Idle
                        _connectionStatus.value = "未连接"
                    }
                }

                override fun onMessage(message: Map<String, Any>) {
                    // 处理收到的信令消息
                    val type = message["type"] as? String
                    val from = message["from"] as? String ?: "unknown"
                    
                    Log.d(TAG, "📨 收到信令消息: type=$type, from=$from")
                    
                    when (type) {
                        "offer" -> {
                            Log.i(TAG, "🔄 收到SDP Offer，开始WebRTC协商")
                            _connectionStatus.value = "收到SDP Offer，协商中..."
                            
                            // 处理SDP Offer
                            val sdp = message["sdp"] as? String
                            if (sdp != null) {
                                webRTCManager?.handleOffer(sdp)
                            } else {
                                Log.e(TAG, "❌ SDP Offer缺少sdp字段")
                            }
                        }
                        "ice-candidate" -> {
                            viewModelScope.launch {
                                try {
                                    // 优化ICE候选处理：避免密集数据处理
                                    Log.d(TAG, "🧊 收到ICE候选，继续连接建立")
                                    _connectionStatus.value = "收到ICE候选"
                                    
                                    // 添加延迟处理，避免消息风暴
                                    if ((message["candidate"] as? String)?.contains("typ srflx") == true) {
                                        // 服务反射候选可能较多，增加延迟
                                        delay(10L)
                                    }
                                    
                                    // 处理ICE候选
                                    val candidate = message["candidate"] as? String
                                    val sdpMid = message["sdpMid"] as? String
                                    val sdpMLineIndex = message["sdpMLineIndex"] as? Int ?: 0
                                    
                                    if (candidate != null) {
                                        webRTCManager?.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
                                        
                                        // 简单统计ICE候选数量（仅调试用）
                                        if (candidate.startsWith("candidate:")) {
                                            Log.d(TAG, "🧊 ICE候选类型: ${candidate.take(50)}...")
                                        }
                                    } else {
                                        Log.e(TAG, "❌ ICE候选缺少candidate字段")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ ICE候选处理异常: ${e.message}")
                                    // 处理异常但不中断连接流程
                                }
                            }
                        }
                        else -> {
                            Log.d(TAG, "📋 收到未知信令类型: $type")
                        }
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "❌ 信令错误: $error")
                    _signalingStatus.value = "信令错误: $error"
                    
                    // 智能重连策略：根据错误类型决定是否重连
                    if (error.contains("EOFException", ignoreCase = true)) {
                        // EOF异常通常表示服务器端中断，延迟重连
                        _connectionStatus.value = "服务器连接中断，稍后重连..."
                        viewModelScope.launch {
                            delay(3000L) // 3秒后重连
                            if (_state.value != ConnectionState.Connected && _state.value != ConnectionState.Idle) {
                                Log.w(TAG, "🔄 EOF异常发生后尝试智能重连")
                                webSocketClient?.connect()
                            } else {
                                Log.w(TAG, "⏸️ 当前状态为 ${_state.value::class.simpleName}，跳过重连")
                            }
                        }
                    } else if (error.contains("timeout", ignoreCase = true) || error.contains("refused", ignoreCase = true)) {
                        // 网络连接问题，保持IDLE状态
                        _state.value = ConnectionState.Idle
                        _connectionStatus.value = "连接失败，请检查网络和服务器状态"
                    } else {
                        // 其他错误类型
                        _state.value = ConnectionState.Idle
                        _connectionStatus.value = "连接错误: $error"
                    }
                }
            })

            webSocketClient?.connect()
        }
    }

    fun stopListening() {
        webSocketClient?.disconnect()
        webSocketClient = null
        
        // 清理WebRTC资源
        _videoTrack.value = null
        _isVideoVisible.value = false
        webRTCManager?.dispose()
        webRTCManager = null
        
        _state.value = ConnectionState.Idle
        _connectionStatus.value = "未连接"
        _signalingStatus.value = "信令未连接"
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient?.disconnect()
        webRTCManager?.dispose()
        webRTCManager = null
    }
}
