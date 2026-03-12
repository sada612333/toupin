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

class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceView: SurfaceView? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
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
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        
        // 注册 MediaProjection 回调
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopScreenCapture()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        
        // 创建 SurfaceView 用于预览
        createPreviewSurface()
    }

    private fun createPreviewSurface() {
        Log.d(TAG, "Creating preview SurfaceView")
        
        // 检查悬浮窗权限
        Log.d(TAG, "Checking SYSTEM_ALERT_WINDOW permission")
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No SYSTEM_ALERT_WINDOW permission")
            return
        }
        Log.d(TAG, "SYSTEM_ALERT_WINDOW permission granted")

        // 创建 SurfaceView
        Log.d(TAG, "Creating SurfaceView instance")
        surfaceView = SurfaceView(this)
        // 设置固定大小（屏幕宽高的1/3）
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val width = (screenWidth / 3).toInt()
        val height = (screenHeight / 3).toInt()
        Log.d(TAG, "SurfaceView size set: width=$width, height=$height (1/3 of screen size)")
        // 为 SurfaceView 添加边框（去掉背景色，只保留边框）
        val borderDrawable = android.graphics.drawable.ShapeDrawable()
        borderDrawable.shape = android.graphics.drawable.shapes.RectShape()
        borderDrawable.paint.color = android.graphics.Color.WHITE
        borderDrawable.paint.style = android.graphics.Paint.Style.STROKE
        borderDrawable.paint.strokeWidth = 2f
        surfaceView?.background = borderDrawable
        // 设置可见性
        surfaceView?.visibility = android.view.View.VISIBLE
        Log.d(TAG, "SurfaceView created: $surfaceView")
        
        // 添加 SurfaceHolder.Callback 监听 Surface 状态
        surfaceView?.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                Log.d(TAG, "Surface created: ${holder.surface}")
                // Surface 创建完成后，创建 VirtualDisplay
                try {
                    createVirtualDisplay()
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating VirtualDisplay: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: format=$format, width=$width, height=$height")
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
            }
        })
        
        // 获取 WindowManager
        Log.d(TAG, "Getting WindowManager")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "WindowManager obtained: $windowManager")
        
        // 设置 SurfaceView 布局参数
        Log.d(TAG, "Creating WindowManager.LayoutParams")
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
        
        // 添加 SurfaceView 到窗口
        try {
            Log.d(TAG, "Attempting to add SurfaceView to window")
            windowManager?.addView(surfaceView, layoutParams)
            Log.d(TAG, "Preview SurfaceView added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding SurfaceView: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createVirtualDisplay() {
        Log.d(TAG, "Creating VirtualDisplay")
        
        // 检查 SurfaceView 是否初始化
        if (surfaceView == null) {
            val errorMsg = "SurfaceView is not initialized"
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
        Log.d(TAG, "surfaceView: $surfaceView")
        
        // 检查 SurfaceHolder 是否有效
        val holder = surfaceView?.holder
        if (holder == null) {
            val errorMsg = "SurfaceHolder is null"
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
        Log.d(TAG, "surfaceView?.holder: $holder")
        
        // 检查 Surface 是否有效
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            val errorMsg = "Surface is not valid: $surface"
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
        Log.d(TAG, "surface: $surface")
        
        // 检查 MediaProjection 是否初始化
        if (mediaProjection == null) {
            val errorMsg = "MediaProjection is not initialized"
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
        Log.d(TAG, "mediaProjection: $mediaProjection")
        
        // 获取 SurfaceView 的实际宽度和高度
        val width = surfaceView?.width ?: 0
        val height = surfaceView?.height ?: 0
        Log.d(TAG, "Using SurfaceView actual size: width=$width, height=$height")
        
        // 创建 VirtualDisplay，使用 SurfaceView 的实际尺寸
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenShare",
            width, // 使用 SurfaceView 实际宽度
            height, // 使用 SurfaceView 实际高度
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    Log.d(TAG, "VirtualDisplay paused")
                }

                override fun onResumed() {
                    Log.d(TAG, "VirtualDisplay resumed")
                }

                override fun onStopped() {
                    Log.d(TAG, "VirtualDisplay stopped")
                }
            },
            null
        )
        Log.d(TAG, "VirtualDisplay created: $virtualDisplay")
        
        if (virtualDisplay == null) {
            val errorMsg = "Failed to create VirtualDisplay"
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }
    }

    private fun stopScreenCapture() {
        Log.d(TAG, "Stopping screen capture")
        virtualDisplay?.release()
        mediaProjection?.stop()
        // 移除 SurfaceView
        if (surfaceView != null && windowManager != null) {
            try {
                windowManager?.removeView(surfaceView)
                Log.d(TAG, "Preview SurfaceView removed from window")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing SurfaceView: ${e.message}")
            }
        }
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
