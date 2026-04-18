package com.lys.toupin.receiver.websocket

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketClient(private val serverUrl: String, private val clientId: String) {
    companion object {
        private const val TAG = "WebSocketClient"
        // 添加消息类型常量以提高可读性
        private const val MAX_LOG_LENGTH = 1000 // 限制日志长度，避免过长
        private const val MAX_RETRY_COUNT = 3 // 最大重试次数
        private const val BASE_RETRY_DELAY_MS = 3000L // 基础重试延迟（毫秒）
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var listener: WebSocketListener? = null
    private var retryCount = 0
    private var isExplicitlyDisconnected = false
    private var reconnectTask: android.os.Handler? = null
    private var isReconnecting = false
    private var heartbeatTask: android.os.Handler? = null
    private val HEARTBEAT_INTERVAL = 3000L // 3秒心跳

    interface WebSocketListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(message: Map<String, Any>)
        fun onError(error: String)
    }

    fun setListener(listener: WebSocketListener) {
        this.listener = listener
    }

    fun connect() {
        if (isExplicitlyDisconnected) {
            Log.w(TAG, "⏸️ 连接被显式断开，禁止自动重连")
            return
        }
        
        if (isReconnecting) {
            Log.w(TAG, "⏸️ 已在重连中，避免重复操作")
            return
        }
        
        isReconnecting = true
        
        try {
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)  // 增加读取超时
                .writeTimeout(60, TimeUnit.SECONDS)  // 增加写入超时
                .pingInterval(20, TimeUnit.SECONDS)  // 添加Ping保持连接
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url(serverUrl)
                .build()

            Log.i(TAG, "🔗 正在连接到WebSocket服务器: $serverUrl (尝试 ${retryCount + 1}/${MAX_RETRY_COUNT})" )
            Log.i(TAG, "🔗 当前重连状态: 尝试次数=${retryCount + 1}, 最大次数=$MAX_RETRY_COUNT")
            
            val wsListener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    retryCount = 0 // 连接成功，重置重试计数
                    isReconnecting = false // 重置重连状态
                    Log.i(TAG, "✅ WebSocket连接成功: $serverUrl")
                    this@WebSocketClient.webSocket = webSocket
                    sendJoinMessage()
                    startHeartbeat() // 启动心跳机制
                    listener?.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = gson.fromJson(text, Map::class.java) as Map<String, Any>
                        val messageType = message["type"] as? String ?: "unknown"
                        val from = message["from"] as? String ?: "unknown"
                        
                        // 优化日志格式，区分不同类型的消息
                        when (messageType) {
                            "offer" -> {
                                // 对SDP消息进行摘要显示，避免过长
                                val sdp = message["sdp"] as? String ?: ""
                                val sdpSummary = if (sdp.length > 200) {
                                    "${sdp.take(150)}... [截断 ${sdp.length - 150} 字符]"
                                } else sdp
                                Log.i(TAG, "📨 收到OFFER消息 from=$from, SDP长度=${sdp.length}")
                                Log.d(TAG, "SDP摘要: ${sdp.take(100)}...")
                            }
                            "ice-candidate" -> {
                                val candidate = message["candidate"] as? String ?: ""
                                Log.d(TAG, "🧊 收到ICE候选 from=$from, 类型=${candidate.takeWhile { it != ' ' }}")
                            }
                            else -> {
                                // 其他消息类型，显示基本信息
                                val logText = if (text.length > MAX_LOG_LENGTH) {
                                    "${text.take(MAX_LOG_LENGTH)}... [截断]"
                                } else text
                                Log.d(TAG, "📨 收到消息 [$messageType] from=$from, 长度=${text.length}:")
                                Log.d(TAG, "内容: $logText")
                            }
                        }
                        
                        listener?.onMessage(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 解析消息失败: ${e.message}")
                        listener?.onError("Error parsing message: ${e.message}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 1000) {
                        Log.i(TAG, "🔌 WebSocket正常关闭: code=$code, reason='$reason'")
                    } else {
                        Log.w(TAG, "⚠️ WebSocket异常关闭: code=$code, reason='$reason'")
                    }
                    stopHeartbeat() // 停止心跳
                    this@WebSocketClient.webSocket = null
                    listener?.onDisconnected()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 改进错误信息显示，避免显示 "null"
                    val errorMessage = if (t.message.isNullOrEmpty()) {
                        t.javaClass.simpleName + " (无详细消息)"
                    } else {
                        t.message ?: "未知错误"
                    }
                    
                    Log.e(TAG, "❌ WebSocket连接失败: $errorMessage")
                    Log.e(TAG, "   错误类型: ${t.javaClass.simpleName}")
                    
                    // 如果是EOF异常，添加更多诊断信息
                    if (t is java.io.EOFException || t.message?.contains("EOF") == true) {
                        Log.e(TAG, "⚠️  EOF诊断: 通常表示服务器连接意外中断")
                        Log.e(TAG, "⚠️  可能原因: 服务器重启、网络不稳定、协议不匹配")
                    }
                    
                    if (response != null) {
                        Log.e(TAG, "   响应码: ${response.code}")
                        if (response.code == 101) {
                            Log.e(TAG, "   协议切换成功，但连接后出现问题")
                        }
                    }
                    
                    // 如果是网络问题（包括EOF异常），尝试重连
                    if (t is java.net.ConnectException || t is java.net.UnknownHostException || 
                        t is java.net.SocketTimeoutException || t is javax.net.ssl.SSLException ||
                        t is java.io.EOFException || t.message?.contains("EOF") == true) {
                        
                        // 详细的EOF错误信息
                        if (t is java.io.EOFException) {
                            Log.e(TAG, "📋 EOF错误详情:")
                            Log.e(TAG, "   - 错误类型: java.io.EOFException")
                            Log.e(TAG, "   - 错误消息: ${t.message ?: "无详细消息"}")
                            Log.e(TAG, "   - 可能原因:")
                            Log.e(TAG, "     1. 服务器主动关闭连接")
                            Log.e(TAG, "     2. 网络连接中断")
                            Log.e(TAG, "     3. 防火墙或代理拦截")
                            Log.e(TAG, "     4. 服务器重启或崩溃")
                        }
                        
                        if (retryCount < MAX_RETRY_COUNT) {
                            val nextRetryCount = retryCount + 1
                            // 指数退避策略：每次重试延迟翻倍
                            val delay = BASE_RETRY_DELAY_MS * (1 shl (nextRetryCount - 1))
                            
                            Log.w(TAG, "🔄 将在 ${delay}ms后尝试重连 ($nextRetryCount/$MAX_RETRY_COUNT)" )
                            Log.w(TAG, "🔄 指数退避策略: 重试次数=$nextRetryCount, 延迟=${delay}ms")
                            
                            // 取消之前的重连任务
                            reconnectTask?.removeCallbacksAndMessages(null)
                            
                            // 延迟重连
                            reconnectTask = android.os.Handler(android.os.Looper.getMainLooper())
                            reconnectTask?.postDelayed({
                                retryCount = nextRetryCount
                                isReconnecting = false
                                connect()
                            }, delay)
                        } else {
                            Log.e(TAG, "💥 已达到最大重试次数 ($MAX_RETRY_COUNT)，停止重连")
                            Log.e(TAG, "💥 请检查网络连接或服务器状态")
                            Log.e(TAG, "💥 建议操作:")
                            Log.e(TAG, "   1. 确认发送端服务器是否运行")
                            Log.e(TAG, "   2. 检查网络连接是否正常")
                            Log.e(TAG, "   3. 验证IP地址和端口是否正确")
                            isReconnecting = false
                        }
                    } else {
                        // 非网络问题，不尝试重连
                        Log.e(TAG, "⚠️ 非网络问题，不尝试重连")
                        isReconnecting = false
                    }
                    
                    // 打印堆栈跟踪的前几行用于调试
                    t.stackTrace.take(3).forEachIndexed { index, stackTraceElement ->
                        if (index == 0) {
                            Log.e(TAG, "   堆栈跟踪: ${stackTraceElement}")
                        }
                    }
                    
                    stopHeartbeat() // 停止心跳
                    this@WebSocketClient.webSocket = null
                    listener?.onError("连接失败: $errorMessage")
                }
            }

            webSocket = client?.newWebSocket(request, wsListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to WebSocket: ${e.message}")
            listener?.onError("Connection error: ${e.message}")
        }
    }

    fun disconnect() {
        isExplicitlyDisconnected = true
        retryCount = MAX_RETRY_COUNT // 阻止自动重连
        isReconnecting = false // 停止重连
        
        // 取消重连任务和心跳任务
        reconnectTask?.removeCallbacksAndMessages(null)
        reconnectTask = null
        stopHeartbeat() // 停止心跳
        
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        
        Log.i(TAG, "🔌 WebSocket连接已断开")
    }

    fun sendMessage(type: String, data: Any? = null, to: String = "android-server") {
        val message = mapOf(
            "type" to type,
            "from" to clientId,
            "to" to to,
            "data" to data
        )
        val jsonMessage = gson.toJson(message)
        webSocket?.send(jsonMessage)
        Log.d(TAG, "📤 发送消息 [$type] to=$to, 长度=${jsonMessage.length}")
        if (jsonMessage.length > 500) {
            Log.d(TAG, "   内容摘要: ${jsonMessage.take(100)}...")
        }
    }

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val message = mapOf(
            "type" to "ice-candidate",
            "from" to clientId,
            "to" to "android-server",
            "candidate" to candidate,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex
        )
        val jsonMessage = gson.toJson(message)
        
        // 优化数据传输：分批确认和延迟发送
        try {
            // 检查连接状态
            if (webSocket == null) {
                Log.w(TAG, "⚠️  尝试发送ICE候选但WebSocket已断开")
                return
            }
            
            // 模拟分批传输：如果数据量过大，添加小延迟
            if (jsonMessage.length > 1000) {
                Log.d(TAG, "📤 大尺寸ICE候选 (${jsonMessage.length}字符)，添加传输延迟")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    webSocket?.send(jsonMessage)
                }, 50L) // 50ms延迟避免网络拥塞
            } else {
                webSocket?.send(jsonMessage)
            }
            
            Log.d(TAG, "🧊 发送ICE候选: type=${candidate.take(30)}..., size=${jsonMessage.length}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ ICE候选发送失败: ${e.message}")
            // 如果发送失败，尝试重建连接
            if (e is java.io.IOException) {
                Log.w(TAG, "🔄 ICE候选发送失败，计划重新连接")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connect()
                }, 1000L)
            }
        }
    }

    fun sendAnswer(sdp: String) {
        val message = mapOf(
            "type" to "answer",
            "from" to clientId,
            "to" to "android-server",
            "sdp" to sdp
        )
        val jsonMessage = gson.toJson(message)
        webSocket?.send(jsonMessage)
        Log.i(TAG, "✅ 发送ANSWER消息, SDP长度=${sdp.length}")
        Log.d(TAG, "   SDP摘要: ${sdp.take(100)}...")
    }

    private fun sendJoinMessage() {
        sendMessage("join", clientId)
    }

    private fun startHeartbeat() {
        // 取消之前的心跳任务
        stopHeartbeat()
        
        // 每3秒发送一次心跳
        heartbeatTask = android.os.Handler(android.os.Looper.getMainLooper())
        heartbeatTask?.postDelayed(object : Runnable {
            override fun run() {
                if (webSocket != null) {
                    val heartbeatMessage = mapOf(
                        "type" to "heartbeat",
                        "from" to clientId,
                        "to" to "android-server",
                        "device" to clientId
                    )
                    val jsonMessage = gson.toJson(heartbeatMessage)
                    webSocket?.send(jsonMessage)
                    Log.d(TAG, "💓 发送心跳消息")
                    
                    // 继续下一次心跳
                    heartbeatTask?.postDelayed(this, HEARTBEAT_INTERVAL)
                }
            }
        }, HEARTBEAT_INTERVAL)
    }

    private fun stopHeartbeat() {
        heartbeatTask?.removeCallbacksAndMessages(null)
        heartbeatTask = null
    }

    fun isConnected(): Boolean {
        return webSocket != null
    }
}
