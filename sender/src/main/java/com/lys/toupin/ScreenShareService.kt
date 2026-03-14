package com.lys.toupin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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

class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var virtualDisplay: VirtualDisplay? = null
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


    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
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
        Log.d(TAG, "Result code: $resultCode")
        
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

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "Initializing MediaProjection")
        
        // 初始化WebRTC组件
        initWebRTC()
        
        // 创建ScreenCapturerAndroid（它会自己处理MediaProjection）
        createScreenCapturer(data)
        
        // 创建 SurfaceViewRenderer 用于预览
        createPreview()
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
