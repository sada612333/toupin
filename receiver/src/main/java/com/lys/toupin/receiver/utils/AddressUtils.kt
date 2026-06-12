package com.lys.toupin.receiver.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 统一的地址校验与历史记录管理。
 *
 * - 用户输入只需 `host:port`；显式 `ws://` / `wss://` / `http://` / `https://`
 *   前缀会被视为非法，引导用户只输入 host:port。
 * - host 支持 IPv4 或域名 / localhost；端口必须为 1 - 65535 的整数。
 * - 历史记录以 JSON 数组保存在 SharedPreferences 中，顺序与最近连接一致；
 *   最多保留 [MAX_HISTORY] 条。
 */
object AddressUtils {

    private const val KEY_HISTORY = "connection_history"
    private const val PREFS_NAME = "receiver_prefs"
    const val MAX_HISTORY = 5

    private val gson by lazy { Gson() }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 规范化用户输入。
     *
     * @return `Result.success("ws://host:port")` 在成功时；失败时异常消息描述问题。
     */
    fun validateAndNormalize(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入地址"))
        }

        val explicitSchemes = listOf("ws://", "wss://", "http://", "https://")
        explicitSchemes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }?.let {
            return Result.failure(
                IllegalArgumentException("无需输入 $it，请直接输入 host:port，例如 192.168.1.100:8888")
            )
        }

        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon < 0) {
            return Result.failure(
                IllegalArgumentException("缺少端口，格式应为 host:port，例如 192.168.1.100:8888")
            )
        }

        val host = trimmed.substring(0, lastColon)
        val portPart = trimmed.substring(lastColon + 1)

        if (host.isEmpty()) return Result.failure(IllegalArgumentException("主机名不能为空"))
        if (portPart.isEmpty()) return Result.failure(IllegalArgumentException("端口不能为空"))

        val port = portPart.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("端口应为数字（1-65535）"))
        if (port !in 1..65535) {
            return Result.failure(IllegalArgumentException("端口范围应为 1-65535"))
        }

        validateHost(host).onFailure { return Result.failure(it) }

        return Result.success("ws://$host:$port")
    }

    /** 仅校验 host，返回错误消息或 null。 */
    private fun validateHost(host: String): Result<Unit> {
        // IPv4
        val ipv4Segments = host.split('.')
        if (ipv4Segments.size == 4 && ipv4Segments.all { it.all(Char::isDigit) }) {
            for (seg in ipv4Segments) {
                val value = seg.toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("IPv4 段必须为数字"))
                if (value > 255) {
                    return Result.failure(IllegalArgumentException("IPv4 每段不能超过 255"))
                }
            }
            return Result.success(Unit)
        }

        // 域名 / localhost
        if (host == "localhost") return Result.success(Unit)
        val parts = host.split('.')
        val isLabel = { label: String ->
            label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
        }
        if (parts.any { !isLabel(it) }) {
            return Result.failure(IllegalArgumentException("主机名含有非法字符"))
        }
        return Result.success(Unit)
    }

    // ---------- 历史记录 ----------

    fun loadHistory(context: Context): List<String> {
        val raw = preferences(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addToHistory(context: Context, address: String): List<String> {
        val display = address.removePrefix("ws://")
            .removePrefix("wss://")
            .trim()
        if (display.isEmpty()) return loadHistory(context)

        val current = loadHistory(context).toMutableList()
        current.remove(display)
        current.add(0, display)
        val trimmed = current.take(MAX_HISTORY)
        preferences(context).edit()
            .putString(KEY_HISTORY, gson.toJson(trimmed))
            .apply()
        return trimmed
    }

    fun removeFromHistory(context: Context, address: String): List<String> {
        val display = address.removePrefix("ws://")
            .removePrefix("wss://")
            .trim()
        val current = loadHistory(context).toMutableList()
        current.remove(display)
        preferences(context).edit()
            .putString(KEY_HISTORY, gson.toJson(current))
            .apply()
        return current
    }

    fun clearHistory(context: Context): List<String> {
        preferences(context).edit().remove(KEY_HISTORY).apply()
        return emptyList()
    }
}
