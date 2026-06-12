package com.lys.toupin.receiver.websocket

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient as JavaWsClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClient(
    private val listener: WebSocketClientListener
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val RECONNECT_INTERVAL_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: JavaWsClient? = null

    @Volatile
    private var serverUri: URI? = null

    private val shouldAutoReconnect = AtomicBoolean(true)
    private val reconnectAttempts = AtomicInteger(0)
    private val isConnecting = AtomicBoolean(false)

    @Volatile
    private var heartbeatJob: Job? = null

    @Volatile
    private var heartbeatAckJob: Job? = null

    @Volatile
    private var lastHeartbeatAckAt = 0L

    private val pendingMessages = ConcurrentLinkedQueue<String>()

    fun connect(address: String) {
        val normalized = normalizeAddress(address)
        if (normalized == null) {
            listener.onConnectionError("地址格式无效")
            return
        }

        try {
            serverUri = URI(normalized)
            shouldAutoReconnect.set(true)
            internalConnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse address: $address", e)
            listener.onConnectionError("地址解析失败")
        }
    }

    fun disconnect() {
        shouldAutoReconnect.set(false)
        stopHeartbeat()
        pendingMessages.clear()
        try {
            client?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client", e)
        }
        client = null
    }

    fun send(type: String, payload: Map<String, Any>) {
        val message = gson.toJson(mapOf("type" to type, "payload" to payload))
        val cur = client
        if (cur != null && cur.isOpen) {
            cur.send(message)
        } else {
            pendingMessages.offer(message)
        }
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    private fun internalConnect() {
        if (isConnecting.getAndSet(true)) {
            return
        }

        listener.onConnecting(reconnectAttempts.get())

        val uri = serverUri ?: run {
            isConnecting.set(false)
            return
        }

        scope.launch {
            try {
                val inner = object : JavaWsClient(uri, Draft_6455(), null, CONNECT_TIMEOUT_MS.toInt()) {
                    override fun onOpen(handshakedata: ServerHandshake?) {
                        Log.d(TAG, "onOpen: connected to $uri")
                        isConnecting.set(false)
                        reconnectAttempts.set(0)
                        listener.onConnected()
                        flushPendingMessages()
                        startHeartbeat()
                    }

                    override fun onMessage(message: String?) {
                        message ?: return
                        handleMessage(message)
                    }

                    override fun onClose(code: Int, reason: String?, remote: Boolean) {
                        Log.d(TAG, "onClose: code=$code reason=$reason remote=$remote")
                        stopHeartbeat()
                        isConnecting.set(false)
                        listener.onDisconnected()
                        scheduleReconnect()
                    }

                    override fun onError(ex: Exception?) {
                        Log.e(TAG, "onError", ex)
                    }
                }

                client = inner
                val connected = inner.connectBlocking(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!connected) {
                    Log.w(TAG, "connectBlocking returned false: connection failed/timed out")
                    isConnecting.set(false)
                    listener.onConnectionError("连接超时或被拒绝")
                    scheduleReconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                isConnecting.set(false)
                listener.onConnectionError("连接失败：${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun handleMessage(message: String) {
        try {
            val json = gson.fromJson(message, Map::class.java) as Map<*, *>
            val type = json["type"] as? String ?: return
            val payload = json["payload"] as? Map<*, *> ?: emptyMap<String, Any>()

            when (type) {
                "heartbeat-ack" -> {
                    lastHeartbeatAckAt = System.currentTimeMillis()
                }

                "stop" -> {
                    Log.d(TAG, "Received stop from sender")
                    shouldAutoReconnect.set(false)
                    listener.onSenderStopped((payload["reason"] as? String) ?: "user_stopped")
                }

                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val castedPayload = payload as Map<String, Any>
                    listener.onMessageReceived(type, castedPayload)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    private fun flushPendingMessages() {
        val cur = client ?: return
        while (pendingMessages.isNotEmpty()) {
            val msg = pendingMessages.poll() ?: break
            try {
                cur.send(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush pending message", e)
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        lastHeartbeatAckAt = System.currentTimeMillis()

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (client?.isOpen == true) {
                    val payload = gson.toJson(
                        mapOf("type" to "heartbeat", "payload" to emptyMap<String, Any>())
                    )
                    try {
                        client?.send(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send heartbeat", e)
                    }
                    scheduleHeartbeatAckTimeout()
                }
            }
        }
    }

    private fun scheduleHeartbeatAckTimeout() {
        heartbeatAckJob?.cancel()
        heartbeatAckJob = scope.launch {
            delay(HEARTBEAT_TIMEOUT_MS)
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatAckAt > HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "Heartbeat timeout, triggering reconnect")
                try {
                    client?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing on timeout", e)
                }
                client = null
                listener.onDisconnected()
                scheduleReconnect()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatAckJob?.cancel()
        heartbeatAckJob = null
    }

    private fun scheduleReconnect() {
        if (!shouldAutoReconnect.get()) return
        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Reconnect attempts ($attempts) exceeded max ($MAX_RECONNECT_ATTEMPTS), giving up")
            shouldAutoReconnect.set(false)
            listener.onConnectionError("重连失败次数过多，请检查发送端是否仍在运行")
            return
        }
        Log.d(TAG, "Scheduling reconnect attempt $attempts")
        listener.onReconnecting(attempts)

        scope.launch {
            delay(RECONNECT_INTERVAL_MS)
            if (shouldAutoReconnect.get()) {
                internalConnect()
            }
        }
    }

    private fun normalizeAddress(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            trimmed
        } else {
            "ws://$trimmed"
        }
    }
}

interface WebSocketClientListener {
    fun onConnecting(attempt: Int)
    fun onConnected()
    fun onDisconnected()
    fun onReconnecting(attempt: Int)
    fun onConnectionError(message: String)
    fun onMessageReceived(type: String, payload: Map<String, Any>)
    fun onSenderStopped(reason: String)
}
