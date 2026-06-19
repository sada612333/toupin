package com.lys.toupin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.lys.toupin.signaling.SignalingListener
import com.lys.toupin.signaling.WebSocketSignalingServer
import com.lys.toupin.webrtc.WebRTCPeerManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.concurrent.Volatile

/**
 * 屏幕投屏发送端 Service。
 *
 * 设计原则（非常重要）：
 * - onCreate / onStartCommand / onDestroy 必须在主线程上极快返回（<100ms），
 *   否则系统会以"前台服务启动超时"为由直接杀应用。
 * - 所有耗时操作（WebRTC 初始化、视频采集、WebSocket 绑定端口）必须在
 *   独立的后台 HandlerThread 上串行执行。
 * - startForeground(NOTIFICATION_ID, ...) 必须在 onStartCommand 开始时立即调用，
 *   且 Notification 的小图标必须是**纯色透明的**（Android 12+ 强制要求）。
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "toupin_screen_cast"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.lys.toupin.START"
        const val ACTION_STOP = "com.lys.toupin.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 与 ViewModel 通信的接口，用于上报状态。由 attach/detach 管理。 */
        @Volatile
        private var serviceListener: ServiceListener? = null

        fun attachListener(listener: ServiceListener) {
            serviceListener = listener
            Log.d(TAG, "attachListener: ${listener.javaClass.simpleName}")
        }

        fun detachListener(listener: ServiceListener) {
            if (serviceListener === listener) {
                serviceListener = null
                Log.d(TAG, "detachListener: ${listener.javaClass.simpleName}")
            }
        }

        private fun reportState(state: ScreenShareState) {
            val listener = serviceListener
            if (listener != null) {
                listener.onStateChanged(state)
            } else {
                Log.w(TAG, "reportState: no listener attached, state=$state")
            }
        }
    }

    interface ServiceListener {
        fun onStateChanged(state: ScreenShareState)
    }

    /** 后台工作线程：所有耗时操作都 post 到这里串行执行。 */
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    /** Worker 线程上的资源字段 —— 仅允许在 worker 线程访问。 */
    private var webSocketServer: WebSocketSignalingServer? = null
    private var peerManager: WebRTCPeerManager? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null

    /** 信令回调 —— 将 WebSocket 消息路由到 WebRTCPeerManager。 */
    private val signalingListener = object : SignalingListener {
        override fun onClientConnected(sessionId: String) {
            Log.i(TAG, "signaling: client connected session=$sessionId")
            runOnWorker {
                peerManager?.createOffer(sessionId)
                updateConnectedDevices()
            }
        }

        override fun onClientDisconnected(sessionId: String) {
            Log.i(TAG, "signaling: client disconnected session=$sessionId")
            runOnWorker {
                peerManager?.removePeerConnection(sessionId)
                updateConnectedDevices()
            }
        }

        override fun onMessageReceived(sessionId: String, type: String, payload: Map<String, Any>) {
            runOnWorker {
                val pm = peerManager ?: return@runOnWorker
                when (type) {
                    "answer" -> {
                        val sdp = payload["sdp"] as? String
                        val sdpType = payload["type"] as? String
                        if (sdp != null && sdpType != null) {
                            pm.setRemoteDescription(sessionId, sdp, sdpType)
                        } else {
                            Log.w(TAG, "signaling: invalid 'answer' payload from $sessionId")
                        }
                    }
                    "ice-candidate" -> {
                        val sdpMid = payload["sdpMid"] as? String
                        val sdpMLineIndex = (payload["sdpMLineIndex"] as? Number)?.toInt()
                        val candidate = payload["candidate"] as? String
                        if (sdpMid != null && sdpMLineIndex != null && candidate != null) {
                            pm.addIceCandidate(sessionId, sdpMid, sdpMLineIndex, candidate)
                        } else {
                            Log.w(TAG, "signaling: invalid 'ice-candidate' payload from $sessionId")
                        }
                    }
                    else -> {
                        Log.w(TAG, "signaling: unknown message type='$type' from $sessionId")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期：主线程，必须极快返回
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate (thread=${Thread.currentThread().name})")

        // 1) 通知渠道（轻量，<10ms）
        createNotificationChannel()

        // 2) 启动后台工作线程（轻量，只是创建 + start）
        workerThread = HandlerThread("screen-share-worker").apply { start() }
        workerHandler = Handler(workerThread.looper)

        Log.i(TAG, "onCreate done")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键：Android 12+ 要求 startForegroundService(intent) 后必须在 10 秒内
        // 在 Service 中调 startForeground()。所以我们**第一时间**发出前台通知，
        // 其他所有耗时初始化都放到 worker 线程。
        val notification = createNotification("投屏服务启动中…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须指定 foregroundServiceType
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "onStartCommand: startForeground called, action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP")
                runOnWorker { stopInternal("action_stop") }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        runOnWorker {
            stopInternal("on_destroy")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                workerThread.quitSafely()
            } else {
                workerThread.quit()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to quit worker thread", t)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 启动流程（全部在 worker 线程执行）
    // ------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        reportState(ScreenShareState.Connecting)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "handleStart: invalid MediaProjection (code=$resultCode, data?=${resultData != null})")
            reportState(ScreenShareState.Error("无效的 MediaProjection 结果"))
            stopSelf()
            return
        }

        runOnWorker {
            try {
                Log.i(TAG, "worker: === start screen share sequence ===")

                // 1) 初始化 WebRTC（含 EGL context + PeerConnectionFactory）
                initializePeerManagerIfNeeded()

                // 2) 初始化视频采集
                setupVideoCapture(resultData)

                // 3) 启动 WebSocket 信令服务器
                startSignalingServer()

                Log.i(TAG, "worker: === start screen share sequence DONE ===")
            } catch (t: Throwable) {
                Log.e(TAG, "worker: start sequence failed", t)
                reportState(ScreenShareState.Error(t.message ?: "启动失败"))
                stopInternal("start_failed")
            }
        }
    }

    private fun runOnWorker(block: () -> Unit) {
        workerHandler.post(block)
    }

    private fun initializePeerManagerIfNeeded() {
        if (peerManager != null) {
            Log.d(TAG, "initializePeerManagerIfNeeded: already initialized")
            return
        }
        val appContext = applicationContext
        val manager = WebRTCPeerManager(appContext) { sessionId, type, payload ->
            webSocketServer?.send(sessionId, type, payload)
        }
        // 同步初始化：内部会在 rtcThread 上执行，当前 worker thread 等待初始化完成
        manager.initialize()
        peerManager = manager
        Log.i(TAG, "initializePeerManagerIfNeeded: done")
    }

    private fun setupVideoCapture(resultData: Intent) {
        val pm = peerManager
            ?: throw IllegalStateException("peerManager not initialized")
        val eglContext = pm.sharedEglBaseContext
            ?: throw IllegalStateException("sharedEglBaseContext is null")
        val factory = pm.peerConnectionFactory

        val newSurfaceTexture = SurfaceTextureHelper.create("CaptureThread", eglContext)
        val newVideoSource: VideoSource = factory.createVideoSource(true)
        val capturer: VideoCapturer = ScreenCapturerAndroid(
            resultData,
            object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system; shutting down service")
                    reportState(ScreenShareState.Error("屏幕投影被系统停止"))
                    runOnWorker { stopInternal("media_projection_stopped") }
                }
            }
        )
        capturer.initialize(newSurfaceTexture, applicationContext, newVideoSource.capturerObserver)
        // 默认分辨率：1280x720 @ 30fps —— 稳定性优先
        capturer.startCapture(1280, 720, 30)
        val track: VideoTrack = factory.createVideoTrack("video_track_0", newVideoSource)
        pm.setVideoSource(track)

        surfaceTextureHelper = newSurfaceTexture
        videoSource = newVideoSource
        videoCapturer = capturer
        localVideoTrack = track
        Log.i(TAG, "setupVideoCapture: ok")
    }

    private fun startSignalingServer() {
        val server = WebSocketSignalingServer(signalingListener)
        val started = server.start()
        if (!started) {
            throw IllegalStateException("WebSocket 信令服务器启动失败")
        }
        webSocketServer = server
        val address = server.localAddress
        Log.i(TAG, "startSignalingServer: listening on $address")
        reportState(
            ScreenShareState.Ready(
                address = address ?: "",
                connectedDevices = emptyList()
            )
        )
    }

    /** 从信令服务器获取当前连接的会话列表，更新 UI 状态。 */
    private fun updateConnectedDevices() {
        val server = webSocketServer ?: return
        val address = server.localAddress ?: return
        val devices = server.getConnectedSessions()
        reportState(
            ScreenShareState.Ready(
                address = address,
                connectedDevices = devices
            )
        )
    }

    // ------------------------------------------------------------------
    // 停止流程（必须严格按顺序释放资源）
    // ------------------------------------------------------------------

    private fun stopInternal(reason: String) {
        Log.i(TAG, "stopInternal: reason=$reason")
        try {
            // 1) 停止信令服务器，防止继续收到消息
            try {
                webSocketServer?.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to stop WebSocket server", t)
            }
            webSocketServer = null

            // 2) 停止视频采集（必须在 dispose capturer 之前）
            try {
                videoCapturer?.stopCapture()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to stop video capture", t)
            }
            try {
                videoCapturer?.dispose()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to dispose video capturer", t)
            }
            videoCapturer = null

            // 3) 释放 SurfaceTextureHelper（必须在 videoSource dispose 之前）
            try {
                surfaceTextureHelper?.dispose()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to dispose surfaceTextureHelper", t)
            }
            surfaceTextureHelper = null

            // 4) 释放 videoTrack / videoSource，通知 peerManager 清理
            localVideoTrack = null
            peerManager?.clearVideoSource()
            try {
                videoSource?.dispose()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to dispose video source", t)
            }
            videoSource = null

            // 5) 释放 WebRTC peer connections / factory / EGL base
            try {
                peerManager?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to release peer manager", t)
            }
            peerManager = null

            reportState(ScreenShareState.Idle)
            Log.i(TAG, "stopInternal: resources released successfully")
        } finally {
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                val existing = nm.getNotificationChannel(CHANNEL_ID)
                if (existing != null) {
                    Log.d(TAG, "createNotificationChannel: already exists")
                    return
                }
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "屏幕投屏",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "屏幕投屏前台服务"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
                Log.d(TAG, "createNotificationChannel: created")
            } catch (t: Throwable) {
                Log.e(TAG, "createNotificationChannel: failed", t)
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, piFlags)

        return NotificationCompatBuilderCompat.build(
            context = this,
            channelId = CHANNEL_ID,
            smallIconResId = R.drawable.ic_notification_small,
            contentTitle = "投屏发送端",
            contentText = text,
            contentIntent = pendingIntent
        )
    }
}

/**
 * 一个极小的兼容性封装 —— 避免在主代码里写很长的 NotificationCompat.Builder 链式调用。
 */
private object NotificationCompatBuilderCompat {
    fun build(
        context: Context,
        channelId: String,
        smallIconResId: Int,
        contentTitle: String,
        contentText: String,
        contentIntent: PendingIntent
    ): Notification {
        return androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconResId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
