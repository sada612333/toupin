package com.lys.toupin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.PeerConnection
import com.google.gson.Gson

class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var surfaceView: SurfaceView? = null
    private var windowManager: WindowManager? = null

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var rootEglBase: EglBase? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isScreenCapturerRunning: Boolean = false

    // WebRTC信令相关组件
    private var webRTCPeerManager: WebRTCPeerManager? = null
    private var webSocketSignalingServer: WebSocketSignalingServer? = null
    private var isSignalingEnabled: Boolean = false
    private var signalingConfig: SignalingConfig? = null
    private val gson = Gson()

    // 目标客户端ID，用于信令通信，默认从配置中获取
    private var targetClientId: String = "receiver"


    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()

        // 初始化信令配置
        signalingConfig = SignalingConfig(this)
    }

    private fun initWebRTC() {
        Log.d(TAG, "Initializing WebRTC components")
        // 初始化EGL上下文
        rootEglBase = EglBase.create()
        // 初始化PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        // 创建PeerConnectionFactory
        val peerConnectionFactoryBuilder = PeerConnectionFactory.builder()
        peerConnectionFactory = peerConnectionFactoryBuilder.createPeerConnectionFactory()
        // 创建SurfaceTextureHelper
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", rootEglBase?.eglBaseContext)
        // 创建VideoSource和VideoTrack
        videoSource = peerConnectionFactory?.createVideoSource(false)
        videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
        Log.d(TAG, "WebRTC components initialized successfully")
    }

    private fun createScreenCapturer(data: Intent) {
        Log.d(TAG, "Creating ScreenCapturerAndroid")

        screenCapturer = ScreenCapturerAndroid(
            data,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped in ScreenCapturer")
                }
            })

        Log.d(TAG, "ScreenCapturerAndroid created: $screenCapturer")

        // 初始化并启动捕获器
        try {
            screenCapturer?.initialize(
                surfaceTextureHelper,
                applicationContext,
                videoSource?.capturerObserver
            )
            Log.d(TAG, "ScreenCapturer initialized")

            // 获取屏幕尺寸
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            screenCapturer?.startCapture(
                screenWidth,
                screenHeight,
                30 // 帧率
            )
            Log.d(TAG, "ScreenCapturer started with resolution: ${screenWidth}x${screenHeight}")
            isScreenCapturerRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ScreenCapturer: ${e.message}")
            e.printStackTrace()
            isScreenCapturerRunning = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, startId: $startId")
        Log.d(TAG, "Intent: $intent")

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ requires specifying the service type
            // Android 14+ (targetSDK 34+) requires this to be mediaProjection if using MediaProjection
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 初始化 MediaProjection
        Log.d(TAG, "Intent: $intent")
        Log.d(TAG, "Intent extras: ${intent?.extras}")
        if (intent?.extras != null) {
            Log.d(TAG, "Extras keys: ${intent.extras?.keySet()}")
            for (key in intent.extras?.keySet()!!) {
                @Suppress("DEPRECATION")
                Log.d(TAG, "Extra $key: ${intent.extras?.get(key)}")
            }
        }
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent?.getParcelableExtra("data"))
        }
        Log.d(TAG, "Data: $data")

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            Log.d(TAG, "Initializing MediaProjection")
            initMediaProjection(resultCode, data)
        } else {
            Log.e(TAG, "Cannot initialize MediaProjection: resultCode=$resultCode, data=$data")
        }

        Log.d(TAG, "Foreground service started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        stopScreenCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "Foreground service stopped")
    }

    private fun initMediaProjection(_resultCode: Int, data: Intent) {
        Log.d(TAG, "Initializing MediaProjection")

        // 首先初始化WebRTC组件，这样才能创建有效的capturerObserver
        initWebRTC()

        // 创建ScreenCapturerAndroid（它会自己处理MediaProjection）
        createScreenCapturer(data)

        // 初始化信令服务（WebRTC连接将在信令服务连接成功后自动启动）
        initSignalingService()

        // 创建 SurfaceViewRenderer 用于预览
        createPreview()

        // WebRTC连接将在信令服务连接成功后自动启动
        Log.d(TAG, "MediaProjection initialized, waiting for signaling connection...")
    }

    private fun createPreview() {
        Log.d(TAG, "Creating preview SurfaceViewRenderer")

        // 检查悬浮窗权限
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No SYSTEM_ALERT_WINDOW permission")
            return
        }
        Log.d(TAG, "SYSTEM_ALERT_WINDOW permission granted")

        // 创建SurfaceViewRenderer
        surfaceViewRenderer = SurfaceViewRenderer(this)

        // 设置固定大小（屏幕宽高的1/3）
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val width = (screenWidth / 3).toInt()
        val height = (screenHeight / 3).toInt()
        Log.d(TAG, "SurfaceViewRenderer size set: width=$width, height=$height (1/3 of screen size)")

        // 为SurfaceViewRenderer添加边框
        val borderDrawable = android.graphics.drawable.ShapeDrawable()
        borderDrawable.shape = android.graphics.drawable.shapes.RectShape()
        borderDrawable.paint.color = android.graphics.Color.WHITE
        borderDrawable.paint.style = android.graphics.Paint.Style.STROKE
        borderDrawable.paint.strokeWidth = 2f
        surfaceViewRenderer?.setBackground(borderDrawable)

        // 初始化SurfaceViewRenderer
        surfaceViewRenderer?.init(
            rootEglBase?.eglBaseContext,
            null // 渲染回调
        )

        // 将VideoTrack添加到SurfaceViewRenderer
        videoTrack?.addSink(surfaceViewRenderer)
        Log.d(TAG, "VideoTrack added to SurfaceViewRenderer")

        // 获取WindowManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "WindowManager obtained: $windowManager")

        // 设置布局参数
        val layoutParams = WindowManager.LayoutParams(
            width, // 使用计算好的宽度
            height, // 使用计算好的高度
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // 设置位置（右上角）
        layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        layoutParams.x = 10
        layoutParams.y = 10
        Log.d(TAG, "Layout params set: $layoutParams")

        // 添加SurfaceViewRenderer到窗口
        try {
            Log.d(TAG, "Attempting to add SurfaceViewRenderer to window")
            windowManager?.addView(surfaceViewRenderer, layoutParams)
            Log.d(TAG, "Preview SurfaceViewRenderer added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding SurfaceViewRenderer: ${e.message}")
            e.printStackTrace()
        }
    }



    /**
     * 初始化信令服务
     */
    private fun initSignalingService() {
        Log.d(TAG, "Initializing signaling service")

        try {
            val config = signalingConfig ?: return

            // 启动本地WebSocket信令服务器
            webSocketSignalingServer = WebSocketSignalingServer(config.getServerPort())

            // 设置信令监听器
            webSocketSignalingServer?.setSignalingListener(object : WebSocketSignalingServer.SignalingListener {
                override fun onClientJoined(clientId: String) {
                    Log.d(TAG, "Browser client joined: $clientId")
                    // 有客户端连接，开始建立WebRTC连接
                    // 注意：不要重复初始化WebRTC，检查是否已初始化
                    if (webRTCPeerManager == null) {
                        targetClientId = clientId
                        initWebRTCPeerManager()
                    } else {
                        targetClientId = clientId
                        Log.d(TAG, "WebRTC已经初始化，直接开始连接")
                        startWebRTCConnection()
                    }
                }

                override fun onClientLeft(clientId: String) {
                    Log.d(TAG, "Browser client left: $clientId")
                    // 客户端断开连接，清理资源
                    if (clientId == targetClientId) {
                        targetClientId = "receiver"
                    }
                }
            })
            
            // 设置服务器消息处理器
            webSocketSignalingServer?.setServerMessageHandler(object : WebSocketSignalingServer.ServerMessageHandler {
                override fun onRemoteIceCandidate(candidate: IceCandidate, fromClientId: String) {
                    Log.d(TAG, "收到来自浏览器的ICE候选: ${candidate.sdp}")
                    // 将浏览器发送的ICE候选添加到本地PeerConnection
                    webRTCPeerManager?.addIceCandidate(candidate)
                }
                
                override fun onRemoteAnswer(answer: SessionDescription, fromClientId: String) {
                    Log.d(TAG, "收到来自浏览器的Answer")
                    // 浏览器已回复answer，设置到本地PeerConnection
                    webRTCPeerManager?.setRemoteDescription(answer)
                }
            })

            if (webSocketSignalingServer?.startServer() == true) {
                Log.d(TAG, "Local WebSocket signaling server started successfully")
                isSignalingEnabled = true
            } else {
                Log.e(TAG, "Failed to start local WebSocket signaling server")
                isSignalingEnabled = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing signaling service: ${e.message}")
        }
    }



    /**
     * 初始化WebRTC对等连接管理器
     */
    private fun initWebRTCPeerManager() {
        Log.d(TAG, "Initializing WebRTC Peer Manager")

        try {
            webRTCPeerManager = WebRTCPeerManager()

                // 设置PeerManager监听器
            webRTCPeerManager?.setPeerManagerListener(object : WebRTCPeerManager.PeerManagerListener {
                override fun onLocalDescription(sessionDescription: SessionDescription) {
                    Log.d(TAG, "Local description created: ${sessionDescription.type}")
                    sendSessionDescription(sessionDescription)
                }
                
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Local ICE candidate generated")
                    sendIceCandidate(candidate)
                }
                
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "Peer connection state changed: $state")
                    // 更新连接状态到UI（可通过广播或事件）
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            Log.d(TAG, "WebRTC connection established")
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.e(TAG, "WebRTC connection failed")
                        }
                        else -> { /* 其他状态处理 */ }
                    }
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "WebRTC Peer Manager error: $error")
                }
            })

            // 初始化WebRTC组件（异步初始化，完成后启动连接）
            webRTCPeerManager?.initialize(this) {
                Log.d(TAG, "WebRTC Peer Manager initialized, starting connection...")
                // 在初始化完成后启动WebRTC连接
                startWebRTCConnection()
            }

            Log.d(TAG, "WebRTC Peer Manager initialization started")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebRTC Peer Manager: ${e.message}")
        }
    }

    /**
     * 发送SDP描述到信令服务
     */
    private fun sendSessionDescription(sessionDescription: SessionDescription) {
        if (isSignalingEnabled && webSocketSignalingServer != null) {
            when (sessionDescription.type) {
                SessionDescription.Type.OFFER -> {
                    webSocketSignalingServer?.sendToAllClients(webSocketSignalingServer?.formatSessionDescription(sessionDescription))
                }
                SessionDescription.Type.ANSWER -> {
                    webSocketSignalingServer?.sendToAllClients(webSocketSignalingServer?.formatSessionDescription(sessionDescription))
                }
                else -> {
                    Log.w(TAG, "Unsupported session description type: ${sessionDescription.type}")
                }
            }
        } else {
            Log.w(TAG, "WebSocket signaling server not available for sending session description")
        }
    }

    /**
     * 发送ICE候选到信令服务
     */
    private fun sendIceCandidate(candidate: IceCandidate) {
        if (isSignalingEnabled && webSocketSignalingServer != null) {
            try {
                val iceCandidateJson = gson.toJson(mapOf(
                    "type" to "ice-candidate",
                    "from" to "android-server",
                    "to" to "receiver",
                    "candidate" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex
                ))
                webSocketSignalingServer?.sendToAllClients(iceCandidateJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending ICE candidate: ${e.message}")
            }
        } else {
            Log.w(TAG, "WebSocket signaling server not available for sending ICE candidate")
        }
    }

    /**
     * 启动WebRTC连接
     */
    private fun startWebRTCConnection() {
        Log.d(TAG, "Starting WebRTC connection")

        // 确保WebRTC组件已初始化
        if (videoSource != null && videoTrack != null && webRTCPeerManager != null) {
            try {
                // 启动本地对等连接
                webRTCPeerManager?.createLocalPeerConnection(videoSource!!, videoTrack!!)
                Log.d(TAG, "WebRTC connection started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting WebRTC connection: ${e.message}")
            }
        } else {
            Log.e(TAG, "WebRTC components not ready for connection")
        }
    }



    private fun stopScreenCapture() {
        Log.d(TAG, "Stopping screen capture")

        // 先移除 SurfaceViewRenderer，避免在停止捕获时出现显示问题
        if (surfaceViewRenderer != null && windowManager != null) {
            try {
                windowManager?.removeView(surfaceViewRenderer)
                Log.d(TAG, "Preview SurfaceViewRenderer removed from window")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing SurfaceViewRenderer: ${e.message}")
            }
            surfaceViewRenderer = null
        }

        // 移除旧的 SurfaceView（如果存在）
        if (surfaceView != null && windowManager != null) {
            try {
                windowManager?.removeView(surfaceView)
                Log.d(TAG, "Preview SurfaceView removed from window")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing SurfaceView: ${e.message}")
            }
            surfaceView = null
        }

        // 停止并释放 ScreenCapturer，只在捕获运行时才调用 stopCapture
        screenCapturer?.let { capturer ->
            try {
                if (isScreenCapturerRunning) {
                    capturer.stopCapture()
                    Log.d(TAG, "ScreenCapturer stopped")
                    isScreenCapturerRunning = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping ScreenCapturer: ${e.message}")
                e.printStackTrace()
            } finally {
                // 无论 stopCapture 是否成功，都要调用 dispose
                try {
                    capturer.dispose()
                    Log.d(TAG, "ScreenCapturer disposed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error disposing ScreenCapturer: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        screenCapturer = null

        // 释放 VirtualDisplay
        try {
            virtualDisplay?.release()
            Log.d(TAG, "VirtualDisplay released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }
        virtualDisplay = null

        // MediaProjection由ScreenCapturerAndroid管理，不需要在这里释放

        // 释放 WebRTC 资源，按相反顺序释放
        try {
            surfaceTextureHelper?.dispose()
            Log.d(TAG, "SurfaceTextureHelper disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing SurfaceTextureHelper: ${e.message}")
        }
        surfaceTextureHelper = null

        try {
            videoTrack?.dispose()
            Log.d(TAG, "VideoTrack disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing VideoTrack: ${e.message}")
        }
        videoTrack = null

        try {
            videoSource?.dispose()
            Log.d(TAG, "VideoSource disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing VideoSource: ${e.message}")
        }
        videoSource = null

        try {
            peerConnectionFactory?.dispose()
            Log.d(TAG, "PeerConnectionFactory disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing PeerConnectionFactory: ${e.message}")
        }
        peerConnectionFactory = null

        try {
            rootEglBase?.release()
            Log.d(TAG, "EglBase released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing EglBase: ${e.message}")
        }
        rootEglBase = null



        try {
            webSocketSignalingServer?.stopServer()
            webSocketSignalingServer = null
            Log.d(TAG, "WebSocket signaling server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket signaling server: ${e.message}")
        }

        // 释放WebRTC对等连接管理器
        try {
            webRTCPeerManager?.dispose()
            webRTCPeerManager = null
            Log.d(TAG, "WebRTC Peer Manager disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing WebRTC Peer Manager: ${e.message}")
        }

        Log.d(TAG, "All screen capture resources released")
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channel")
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕共享",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "屏幕共享服务"
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }

    private fun createNotification(): Notification {
        Log.d(TAG, "Creating notification")
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享")
            .setContentText("点击返回应用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        Log.d(TAG, "Notification created")
        return notification
    }
}
