package com.lys.toupin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lys.toupin.signaling.SignalingListener
import com.lys.toupin.signaling.WebSocketSignalingServer
import com.lys.toupin.webrtc.WebRTCPeerManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class ScreenShareService : Service() {
    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.lys.toupin.START"
        const val ACTION_STOP = "com.lys.toupin.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var serviceListener: ServiceListener? = null

        fun setListener(listener: ServiceListener) {
            serviceListener = listener
        }
    }

    interface ServiceListener {
        fun onStateChanged(state: ScreenShareState)
    }

    private var mediaProjectionResultCode: Int = -1
    private var mediaProjectionResultData: Intent? = null
    private var webSocketServer: WebSocketSignalingServer? = null
    private var webRTCPeerManager: WebRTCPeerManager? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val connectedDevices = mutableListOf<String>()
    private val gson = com.google.gson.Gson()

    private val signalingListener = object : SignalingListener {
        override fun onClientConnected(sessionId: String) {
            Log.d(TAG, "Client connected: $sessionId")
            connectedDevices.add(sessionId)
            updateReadyState()
            webRTCPeerManager?.createOffer(sessionId)
        }

        override fun onClientDisconnected(sessionId: String) {
            Log.d(TAG, "Client disconnected: $sessionId")
            connectedDevices.remove(sessionId)
            webRTCPeerManager?.removePeerConnection(sessionId)
            updateReadyState()
        }

        override fun onMessageReceived(sessionId: String, type: String, payload: Map<String, Any>) {
            when (type) {
                "answer" -> {
                    val sdp = payload["sdp"] as? String ?: return
                    val sdpType = payload["type"] as? String ?: return
                    webRTCPeerManager?.setRemoteDescription(sessionId, sdp, sdpType)
                }
                "ice-candidate" -> {
                    val sdpMid = payload["sdpMid"] as? String ?: return
                    val sdpMLineIndex = (payload["sdpMLineIndex"] as? Double)?.toInt() ?: return
                    val candidate = payload["candidate"] as? String ?: return
                    webRTCPeerManager?.addIceCandidate(sessionId, sdpMid, sdpMLineIndex, candidate)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        webSocketServer = WebSocketSignalingServer(signalingListener)
        webRTCPeerManager = WebRTCPeerManager(this) { sessionId, type, payload ->
            webSocketServer?.send(sessionId, type, payload)
        }
        webRTCPeerManager?.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("投屏服务运行中"))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startScreenShare(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopScreenShare()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startScreenShare(resultCode: Int, resultData: Intent?) {
        try {
            serviceListener?.onStateChanged(ScreenShareState.Connecting)

            mediaProjectionResultCode = resultCode
            mediaProjectionResultData = resultData

            // 只设置视频捕获，让 ScreenCapturerAndroid 自己管理 MediaProjection
            // 暂时禁用音频捕获，避免重复使用 resultData 的问题
            setupVideoCapture()

            if (webSocketServer?.start() == true) {
                val address = webSocketServer?.localAddress ?: ""
                updateReadyState()
            } else {
                serviceListener?.onStateChanged(ScreenShareState.Error("启动服务失败"))
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen share", e)
            serviceListener?.onStateChanged(ScreenShareState.Error(e.message ?: "启动失败"))
            stopSelf()
        }
    }

    private fun setupVideoCapture() {
        val eglBase = org.webrtc.EglBase.create()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        val videoSource: VideoSource = webRTCPeerManager!!.peerConnectionFactory.createVideoSource(false)

        videoCapturer = ScreenCapturerAndroid(mediaProjectionResultData!!, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        })
        videoCapturer?.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        videoTrack = webRTCPeerManager!!.peerConnectionFactory.createVideoTrack("video_track", videoSource)

        webRTCPeerManager?.setVideoSource(videoTrack!!)
    }



    private fun updateReadyState() {
        val address = webSocketServer?.localAddress ?: ""
        serviceListener?.onStateChanged(
            ScreenShareState.Ready(address, connectedDevices.toList())
        )
    }

    private fun stopScreenShare() {
        webSocketServer?.stop()
        webSocketServer = null

        // 先停止视频捕获
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video capture", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        videoTrack?.dispose()
        videoTrack = null

        webRTCPeerManager?.release()
        webRTCPeerManager = null

        connectedDevices.clear()

        serviceListener?.onStateChanged(ScreenShareState.Idle)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕投屏服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("投屏发送端")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopScreenShare()
    }
}
