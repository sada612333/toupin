package com.lys.toupin.signaling

interface SignalingListener {
    fun onClientConnected(sessionId: String)
    fun onClientDisconnected(sessionId: String)
    fun onMessageReceived(sessionId: String, type: String, payload: Map<String, Any>)
}
