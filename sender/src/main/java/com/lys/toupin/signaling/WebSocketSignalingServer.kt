package com.lys.toupin.signaling

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebSocketSignalingServer(
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "WebSocketSignalingServer"
        private const val DEFAULT_PORT = 8888
        private const val BACKUP_PORT = 8889
    }

    private var server: SignalingServer? = null
    private val sessions = ConcurrentHashMap<String, WebSocket>()
    private val webSocketToSessionId = ConcurrentHashMap<WebSocket, String>()
    private val sessionCounter = AtomicInteger(0)
    private val gson = Gson()

    var localAddress: String? = null
        private set

    /**
     * 仅包含 host:port 的展示地址（用于向用户展示 / 复制，不包含协议前缀）。
     */
    val displayAddress: String?
        get() = localAddress?.removePrefix("ws://")?.removePrefix("wss://")

    fun start(port: Int = DEFAULT_PORT): Boolean {
        try {
            var actualPort = port
            var serverStarted = false

            while (!serverStarted) {
                try {
                    server = SignalingServer(actualPort)
                    server?.start()
                    serverStarted = true
                    Log.d(TAG, "Server started on port $actualPort")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start server on port $actualPort", e)
                    when (actualPort) {
                        DEFAULT_PORT -> actualPort = BACKUP_PORT
                        BACKUP_PORT -> {
                            actualPort = (10000..60000).random()
                        }
                        else -> return false
                    }
                }
            }

            localAddress = getLocalIPAddress()?.let { "ws://$it:$actualPort" }
            Log.d(TAG, "Server address: $localAddress")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            return false
        }
    }

    fun stop() {
        broadcastStop()
        try {
            sessions.values.forEach { it.close() }
            sessions.clear()
            webSocketToSessionId.clear()
            server?.stop()
            server = null
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun send(sessionId: String, type: String, payload: Map<String, Any>) {
        sessions[sessionId]?.let { conn ->
            try {
                val message = gson.toJson(mapOf("type" to type, "payload" to payload))
                conn.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to $sessionId", e)
            }
        }
    }

    fun broadcast(type: String, payload: Map<String, Any>) {
        val message = gson.toJson(mapOf("type" to type, "payload" to payload))
        sessions.values.forEach { conn ->
            try {
                conn.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast message", e)
            }
        }
    }

    private fun broadcastStop() {
        broadcast("stop", mapOf("reason" to "user_stopped"))
    }

    fun getConnectedSessions(): List<String> = sessions.keys.toList()

    private fun getLocalIPAddress(): String? {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            val preferredIPs = mutableListOf<String>()  // 最优先：192.168.x.x
            val secondaryIPs = mutableListOf<String>()  // 次优先：172.16-31.x.x
            val fallbackIPs = mutableListOf<String>()   // 备选：其他非模拟器地址

            for (networkInterface in interfaces) {
                val ifaceName = networkInterface.name.lowercase()
                val displayName = networkInterface.displayName.lowercase()
                
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    Log.d(TAG, "Skipping interface $ifaceName: loopback or not up")
                    continue
                }
                
                // 过滤虚拟网络接口（Android 模拟器的 eth0、radio0、rmnet 等）
                val virtualInterfaceNames = listOf("dummy", "rmnet", "radio", "vsock", "p2p")
                if (virtualInterfaceNames.any { ifaceName.contains(it) || displayName.contains(it) }) {
                    Log.d(TAG, "Skipping virtual interface: $ifaceName")
                    continue
                }

                val addresses: List<InetAddress> = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    val hostAddress = address.hostAddress ?: continue

                    // 跳过 IPv6 地址
                    if (hostAddress.contains(':')) continue

                    // 排除 Android 模拟器虚拟网络地址段 10.0.2.x
                    if (hostAddress.startsWith("10.0.2.")) {
                        Log.d(TAG, "Skipping AVD virtual network IP: $hostAddress on $ifaceName")
                        continue
                    }

                    // 按优先级分类
                    when {
                        hostAddress.startsWith("192.168.") -> {
                            preferredIPs.add(hostAddress)
                            Log.d(TAG, "Found preferred IP: $hostAddress on $ifaceName")
                        }
                        hostAddress.startsWith("172.") && hostAddress.split(".")[1].toIntOrNull() in 16..31 -> {
                            secondaryIPs.add(hostAddress)
                            Log.d(TAG, "Found secondary IP: $hostAddress on $ifaceName")
                        }
                        else -> {
                            fallbackIPs.add(hostAddress)
                            Log.d(TAG, "Found fallback IP: $hostAddress on $ifaceName")
                        }
                    }
                }
            }

            val allIPs = preferredIPs + secondaryIPs + fallbackIPs
            Log.d(TAG, "IP selection - preferred: $preferredIPs, secondary: $secondaryIPs, fallback: $fallbackIPs")
            Log.d(TAG, "Final candidate IPs: $allIPs")

            return allIPs.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    private inner class SignalingServer(port: Int) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val sessionId = "session_${sessionCounter.incrementAndGet()}"
            val remoteAddress = conn.remoteSocketAddress?.address?.hostAddress
            Log.d(TAG, "New client connecting: $sessionId from $remoteAddress")
            sessions[sessionId] = conn
            webSocketToSessionId[conn] = sessionId
            Log.d(TAG, "Current sessions: ${sessions.size}")
            CoroutineScope(Dispatchers.Main).launch {
                listener.onClientConnected(sessionId)
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val sessionId = webSocketToSessionId.remove(conn)
            if (sessionId != null) {
                Log.d(TAG, "Client disconnected: $sessionId (code: $code, reason: $reason, remote: $remote)")
                sessions.remove(sessionId)
                Log.d(TAG, "Remaining sessions: ${sessions.size}")
                CoroutineScope(Dispatchers.Main).launch {
                    listener.onClientDisconnected(sessionId)
                }
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val sessionId = webSocketToSessionId[conn] ?: return
            try {
                val json = gson.fromJson(message, Map::class.java) as Map<String, Any>
                val type = json["type"] as? String ?: return
                val payload = json["payload"] as? Map<String, Any> ?: emptyMap()

                when (type) {
                    "heartbeat" -> {
                        conn.send(gson.toJson(mapOf("type" to "heartbeat-ack", "payload" to emptyMap<String, Any>())))
                    }
                    else -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            listener.onMessageReceived(sessionId, type, payload)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket error", ex)
        }

        override fun onStart() {
            Log.d(TAG, "WebSocket server started successfully")
        }
    }
}
