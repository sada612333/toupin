package com.lys.toupin

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

import java.io.IOException
import com.google.gson.Gson

/**
 * 嵌入式WebSocket信令服务器，处理客户端间通信
 */
class WebSocketSignalingServer(private val host: String = "0.0.0.0", private val port: Int = 8080) : NanoWSD(host, port) {
    companion object {
        private const val TAG = "SignalingServer"
    }
    
    private val gson = Gson()
    private val connectedClients = mutableMapOf<String, SignalingWebSocket>()
    
    /**
     * 信令服务器监听器，用于通知外部有关连接事件
     */
    interface SignalingListener {
        fun onClientJoined(clientId: String)
        fun onClientLeft(clientId: String)
    }
    
    private var signalingListener: SignalingListener? = null
    
    /**
     * 服务器消息处理接口
     */
    interface ServerMessageHandler {
        fun onRemoteIceCandidate(candidate: IceCandidate, fromClientId: String)
        fun onRemoteAnswer(answer: SessionDescription, fromClientId: String)
    }
    
    private var serverMessageHandler: ServerMessageHandler? = null
    
    /**
     * 设置信令监听器
     */
    fun setSignalingListener(listener: SignalingListener) {
        this.signalingListener = listener
    }
    
    /**
     * 设置服务器消息处理器
     */
    fun setServerMessageHandler(handler: ServerMessageHandler) {
        this.serverMessageHandler = handler
    }
    
    private inner class SignalingWebSocket(handshake: NanoHTTPD.IHTTPSession) 
        : NanoWSD.WebSocket(handshake) {
        
        var clientId: String? = null
        
        override fun onOpen() {
            Log.d(TAG, "WebSocket client connected")
        }
        
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            Log.d(TAG, "WebSocket client disconnected: $reason")
            if (clientId != null) {
                connectedClients.remove(clientId)
                signalingListener?.onClientLeft(clientId!!)
            }
        }
        
        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            try {
                val jsonMessage = message.textPayload
                Log.d(TAG, "Received message: $jsonMessage")
                
                val signalingMessage = gson.fromJson(jsonMessage, Map::class.java)
                val type = signalingMessage["type"] as? String ?: return
                val fromClientId = signalingMessage["from"] as? String
                val targetClientId = signalingMessage["to"] as? String
                
                // 处理不同类型的消息
                when (type) {
                    "join" -> {
                        val joinClientId = signalingMessage["data"] as? String
                        
                        if (joinClientId != null) {
                            this.clientId = joinClientId
                            connectedClients[joinClientId] = this
                            Log.d(TAG, "Client $joinClientId joined the room")
                            // 通知监听器有客户端加入
                            signalingListener?.onClientJoined(joinClientId)
                        }
                        
                        // 关键修复：android-server是服务器端标识，不要注册为客户端
                        // 消息路由会在broadcastMessage中处理android-server目的地的消息
                        Log.d(TAG, "Join request processed for $joinClientId")
                    }
                    "offer", "answer", "ice-candidate" -> {
                        if (fromClientId != null && targetClientId != null) {
                            // 特殊处理：如果目标地址是android-server，则直接调用ScreenShareService处理
                            if (targetClientId == "android-server" || targetClientId == "android") {
                                // 直接调用服务器消息处理器进行处理
                                Log.d(TAG, "Handling server message of type: $type from $fromClientId")
                                handleServerMessage(type, jsonMessage, fromClientId)
                            } else {
                                broadcastMessage(jsonMessage, fromClientId, targetClientId)
                            }
                        }
                    }
                    "heartbeat" -> {
                        // 处理心跳消息，直接忽略，保持连接活跃
                        Log.d(TAG, "💓 收到心跳消息 from $fromClientId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message: ${e.message}")
            }
        }
        
        override fun onPong(pong: NanoWSD.WebSocketFrame) {
            // 处理pong消息
        }
        
        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception: ${exception.message}")
        }
    }
    
    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket {
        return SignalingWebSocket(handshake)
    }
    
    /**
     * 处理服务器消息（消息目标是android-server）
     */
    private fun handleServerMessage(type: String, message: String, fromClientId: String) {
        Log.d(TAG, "Processing server message of type $type from $fromClientId")
        
        try {
            when (type) {
                "ice-candidate" -> {
                    try {
                        val signalingMessage = gson.fromJson(message, Map::class.java)
                        val candidateStr = signalingMessage["candidate"] as? String
                        val sdpMid = signalingMessage["sdpMid"] as? String
                        val sdpMLineIndex = signalingMessage["sdpMLineIndex"] as? Int
                        
                        Log.d(TAG, "Parsing ICE candidate: candidate=$candidateStr, sdpMid=$sdpMid, sdpMLineIndex=$sdpMLineIndex")
                        
                        // 修复：sdpMLineIndex可能为null，使用默认值0
                        val validSdpMLineIndex = sdpMLineIndex ?: 0
                        
                        if (candidateStr != null && sdpMid != null) {
                            val candidate = IceCandidate(sdpMid, validSdpMLineIndex, candidateStr)
                            Log.d(TAG, "Successfully created ICE candidate with sdpMLineIndex=$validSdpMLineIndex, calling handler")
                            serverMessageHandler?.onRemoteIceCandidate(candidate, fromClientId)
                        } else {
                            Log.e(TAG, "Failed to parse ICE candidate: candidate=$candidateStr, sdpMid=$sdpMid")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing ICE candidate message: ${e.message}")
                    }
                }
                "answer" -> {
                    val signalingMessage = gson.fromJson(message, Map::class.java)
                    val sdp = signalingMessage["sdp"] as? String
                    
                    if (sdp != null) {
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        serverMessageHandler?.onRemoteAnswer(answer, fromClientId)
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported server message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message: ${e.message}")
        }
    }
    
    /**
     * 广播消息给指定客户端
     */
    private fun broadcastMessage(message: String, fromClientId: String, toClientId: String) {
        val targetClient = connectedClients[toClientId]
        if (targetClient != null) {
            try {
                Log.d(TAG, "Broadcasting message from $fromClientId to $toClientId")
                targetClient.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting message: ${e.message}")
                // 移除断开连接的客户端
                connectedClients.remove(toClientId)
            }
        } else {
            Log.e(TAG, "Target client $toClientId not found")
        }
    }
    
    /**
     * 启动信令服务器
     */
    fun startServer(): Boolean {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Signaling server started on $host:$port")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting signaling server: ${e.message}")
            return false
        }
    }
    
    /**
     * 停止信令服务器
     */
    fun stopServer() {
        try {
            stop()
            connectedClients.clear()
            Log.d(TAG, "Signaling server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping signaling server: ${e.message}")
        }
    }
    
    /**
     * 获取已连接的客户端数量
     */
    fun getConnectedClientCount(): Int {
        return connectedClients.size
    }
    
    /**
     * 检查特定客户端是否在线
     */
    fun isClientConnected(clientId: String): Boolean {
        return connectedClients[clientId]?.isOpen == true
    }
    
    /**
     * 向所有连接的客户端发送消息
     */
    fun sendToAllClients(message: String?) {
        if (message == null) return
        
        val clientsToRemove = mutableListOf<String>()
        for ((clientId, client) in connectedClients) {
            try {
                client.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to client $clientId: ${e.message}")
                clientsToRemove.add(clientId)
            }
        }
        // 移除断开连接的客户端
        for (clientId in clientsToRemove) {
            connectedClients.remove(clientId)
        }
    }
    
    /**
     * 格式化SessionDescription为信令消息
     */
    fun formatSessionDescription(description: SessionDescription): String {
        return gson.toJson(mapOf(
            "type" to description.type.canonicalForm().lowercase(),
            "from" to "android-server",
            "to" to "receiver",
            "sdp" to description.description
        ))
    }
}