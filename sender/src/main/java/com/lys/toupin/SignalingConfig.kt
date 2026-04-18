package com.lys.toupin

import android.content.Context
import android.content.SharedPreferences

/**
 * 信令配置管理类
 */
class SignalingConfig(private val context: Context) {
    companion object {
        private const val PREF_NAME = "signaling_config"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_TARGET_CLIENT_ID = "target_client_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_USE_LOCAL_SERVER = "use_local_server"
        
        // 默认配置
        private const val DEFAULT_SERVER_URL = "ws://localhost"
        private const val DEFAULT_SERVER_PORT = 8080
        private const val DEFAULT_SERVER_HOST = "0.0.0.0"
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取客户端ID
     */
    fun getClientId(): String {
        var clientId = sharedPreferences.getString(KEY_CLIENT_ID, null)
        if (clientId == null) {
            clientId = "sender_${System.currentTimeMillis()}"
            setClientId(clientId)
        }
        return clientId
    }
    
    /**
     * 设置客户端ID
     */
    fun setClientId(clientId: String) {
        sharedPreferences.edit().putString(KEY_CLIENT_ID, clientId).apply()
    }
    
    /**
     * 获取目标客户端ID
     */
    fun getTargetClientId(): String? {
        return sharedPreferences.getString(KEY_TARGET_CLIENT_ID, null)
    }
    
    /**
     * 设置目标客户端ID
     */
    fun setTargetClientId(targetClientId: String) {
        sharedPreferences.edit().putString(KEY_TARGET_CLIENT_ID, targetClientId).apply()
    }
    
    /**
     * 获取服务器URL
     */
    fun getServerUrl(): String {
        return sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }
    
    /**
     * 设置服务器URL
     */
    fun setServerUrl(serverUrl: String) {
        sharedPreferences.edit().putString(KEY_SERVER_URL, serverUrl).apply()
    }
    
    /**
     * 获取服务器端口
     */
    fun getServerPort(): Int {
        return sharedPreferences.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
    }
    
    /**
     * 设置服务器端口
     */
    fun setServerPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_SERVER_PORT, port).apply()
    }
    
    /**
     * 获取服务器主机地址
     */
    fun getServerHost(): String {
        return sharedPreferences.getString(KEY_SERVER_HOST, DEFAULT_SERVER_HOST) ?: DEFAULT_SERVER_HOST
    }
    
    /**
     * 设置服务器主机地址
     */
    fun setServerHost(host: String) {
        sharedPreferences.edit().putString(KEY_SERVER_HOST, host).apply()
    }
    
    /**
     * 是否使用本地服务器
     */
    fun useLocalServer(): Boolean {
        return sharedPreferences.getBoolean(KEY_USE_LOCAL_SERVER, true)
    }
    
    /**
     * 设置是否使用本地服务器
     */
    fun setUseLocalServer(useLocal: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_USE_LOCAL_SERVER, useLocal).apply()
    }
    
    /**
     * 获取完整的WebSocket URL
     */
    fun getWebSocketUrl(): String {
        val baseUrl = if (useLocalServer()) {
            "ws://localhost"
        } else {
            getServerUrl()
        }
        return "$baseUrl:${getServerPort()}"
    }
    
    /**
     * 验证配置是否完整
     */
    fun isConfigValid(): Boolean {
        return getClientId().isNotEmpty() && getTargetClientId()?.isNotEmpty() == true
    }
    
    /**
     * 清除所有配置
     */
    fun clearConfig() {
        sharedPreferences.edit().clear().apply()
    }
}