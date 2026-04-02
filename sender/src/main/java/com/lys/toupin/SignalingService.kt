package com.lys.toupin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit
import com.google.gson.Gson

/**
 * 信令服务类，负责处理WebSocket连接、SDP交换和ICE候选交换
 */
class SignalingService {
    companion object {
        private const val TAG = "SignalingService"
        
        // 信令消息类型
        private const val TYPE_JOIN = "join"
        private const val TYPE_OFFER = "offer"
        private const val TYPE_ANSWER = "answer"
        private const val TYPE_ICE_CANDIDATE = "ice-candidate"
        private const val TYPE_ERROR = "error"
    }
    
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // 保持连接活跃
        .build()
    
    // 回调接口
    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onRemoteOffer(offer: SessionDescription)
        fun onRemoteAnswer(answer: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
    }
    
    private var signalingListener: SignalingListener? = null
    
    // 信令消息数据类
    data class SignalingMessage(
        val type: String,
        val data: Any? = null,
        val from: String? = null,
        val to: String? = null
    )
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return webSocket != null
    }
    
    /**
     * 发送加入房间消息
     */
    fun sendJoin(clientId: String? = null) {
        val message = SignalingMessage(TYPE_JOIN, clientId ?: "screen_capture")
        sendMessage(message)
    }
    
    /**
     * 连接到信令服务器
     * @param serverUrl WebSocket服务器地址
     * @param clientId 客户端ID
     */
    fun connect(serverUrl: String, clientId: String) {
        try {
            Log.d(TAG, "Connecting to signaling server: $serverUrl")
            
            val request = Request.Builder()
                .url(serverUrl)
                .build()
            
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected")
                    
                    // 发送加入房间消息
                    val joinMessage = SignalingMessage(TYPE_JOIN, clientId)
                    sendMessage(joinMessage)
                    
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onConnected()
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Received message: $text")
                    handleMessage(text)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $reason")
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onDisconnected()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onError(t.message ?: "Connection failed")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to signaling server: ${e.message}")
            signalingListener?.onError("Connection error: ${e.message}")
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            Log.d(TAG, "Disconnected from signaling server")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }
    
    /**
     * 发送SDP Offer
     */
    fun sendOffer(offer: SessionDescription, targetClientId: String? = null) {
        val offerData = mapOf(
            "type" to offer.type.canonicalForm(),
            "sdp" to offer.description
        )
        val message = SignalingMessage(TYPE_OFFER, offerData, to = targetClientId)
        sendMessage(message)
    }
    
    /**
     * 发送SDP Answer
     */
    fun sendAnswer(answer: SessionDescription, targetClientId: String? = null) {
        val answerData = mapOf(
            "type" to answer.type.canonicalForm(),
            "sdp" to answer.description
        )
        val message = SignalingMessage(TYPE_ANSWER, answerData, to = targetClientId)
        sendMessage(message)
    }
    
    /**
     * 发送ICE候选
     */
    fun sendIceCandidate(candidate: IceCandidate, targetClientId: String? = null) {
        val candidateData = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        val message = SignalingMessage(TYPE_ICE_CANDIDATE, candidateData, to = targetClientId)
        sendMessage(message)
    }
    
    /**
     * 发送错误消息
     */
    fun sendError(error: String, targetClientId: String? = null) {
        val message = SignalingMessage(TYPE_ERROR, error, to = targetClientId)
        sendMessage(message)
    }
    
    /**
     * 设置信令监听器
     */
    fun setSignalingListener(listener: SignalingListener) {
        this.signalingListener = listener
    }
    
    /**
     * 发送消息到服务器
     */
    private fun sendMessage(message: SignalingMessage) {
        try {
            val jsonMessage = gson.toJson(message)
            Log.d(TAG, "Sending message: $jsonMessage")
            webSocket?.send(jsonMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
        }
    }
    
    /**
     * 处理接收到的消息
     */
    private fun handleMessage(jsonMessage: String) {
        try {
            val message = gson.fromJson(jsonMessage, SignalingMessage::class.java)
            
            when (message.type) {
                TYPE_OFFER -> {
                    val offerData = gson.fromJson(gson.toJson(message.data), Map::class.java)
                    val sdpType = SessionDescription.Type.fromCanonicalForm(offerData["type"] as String)
                    val sdp = offerData["sdp"] as String
                    val offer = SessionDescription(sdpType, sdp)
                    
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onRemoteOffer(offer)
                    }
                }
                
                TYPE_ANSWER -> {
                    val answerData = gson.fromJson(gson.toJson(message.data), Map::class.java)
                    val sdpType = SessionDescription.Type.fromCanonicalForm(answerData["type"] as String)
                    val sdp = answerData["sdp"] as String
                    val answer = SessionDescription(sdpType, sdp)
                    
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onRemoteAnswer(answer)
                    }
                }
                
                TYPE_ICE_CANDIDATE -> {
                    val candidateData = gson.fromJson(gson.toJson(message.data), Map::class.java)
                    val sdpMid = candidateData["sdpMid"] as? String ?: ""
                    val sdpMLineIndex = candidateData["sdpMLineIndex"] as? Int ?: 0
                    val candidate = candidateData["candidate"] as String
                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onIceCandidate(iceCandidate)
                    }
                }
                
                TYPE_ERROR -> {
                    val error = message.data as? String ?: "Unknown error"
                    GlobalScope.launch(Dispatchers.Main) {
                        signalingListener?.onError(error)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
            GlobalScope.launch(Dispatchers.Main) {
                signalingListener?.onError("Message handling error: ${e.message}")
            }
        }
    }
}