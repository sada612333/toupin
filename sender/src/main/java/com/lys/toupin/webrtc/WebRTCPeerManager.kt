package com.lys.toupin.webrtc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

class WebRTCPeerManager(
    private val context: Context,
    private val signalingSender: (sessionId: String, type: String, payload: Map<String, Any>) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCPeerManager"
        private const val VIDEO_MAX_BITRATE_BPS = 4_000_000
        private const val VIDEO_MIN_BITRATE_BPS = 800_000
        private const val VIDEO_MAX_FRAMERATE = 30

        // ICE Restart 去抖窗口：同一条 session 在该时间内只允许一次 restart
        private const val ICE_RESTART_INTERVAL_MS = 5000L

        // DISCONNECTED → FAILED 的等待时间：给 ICE 自动恢复的机会
        private const val ICE_DISCONNECTED_GRACE_MS = 3000L
    }

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val iceRestartAt = ConcurrentHashMap<String, Long>()

    // 所有 WebRTC 操作统一串行到 rtcThread，避免竞态
    private val rtcThread = HandlerThread("rtc-thread").also { it.start() }
    private val rtcHandler = Handler(rtcThread.looper)

    // 以下字段仅允许在 rtcThread 中写入 / 读取
    private var _peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    /**
     * 同步初始化。返回后，`peerConnectionFactory` 与 `sharedEglBaseContext` 可立即使用。
     * 多次调用是幂等的。
     */
    fun initialize() {
        val latch = java.util.concurrent.CountDownLatch(1)
        var initError: Throwable? = null

        rtcHandler.post {
            try {
                if (_peerConnectionFactory != null) return@post

                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions()
                )

                val newEglBase = EglBase.create()
                _peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(newEglBase.eglBaseContext))
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(
                            newEglBase.eglBaseContext,
                            true,
                            true
                        )
                    )
                    .createPeerConnectionFactory()
                eglBase = newEglBase

                Log.i(TAG, "WebRTC initialized")
            } catch (t: Throwable) {
                Log.e(TAG, "initialize failed", t)
                initError = t
                _peerConnectionFactory = null
                eglBase = null
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Timed out waiting for WebRTC initialization", ie)
        }
        if (initError != null) {
            throw IllegalStateException("WebRTC initialization failed", initError)
        }
    }

    /** EGL 上下文，供 ScreenShareService 初始化 ScreenCapturerAndroid 时复用。 */
    val sharedEglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    val peerConnectionFactory: PeerConnectionFactory
        get() = _peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory is not initialized or already disposed")

    fun setVideoSource(videoTrack: VideoTrack) = runOnRtcThread { localVideoTrack = videoTrack }

    fun clearVideoSource() = runOnRtcThread { localVideoTrack = null }

    fun setAudioSource(audioTrack: AudioTrack) = runOnRtcThread { localAudioTrack = audioTrack }

    // ------------------------------------------------------------------
    // Offer / Answer / ICE
    // ------------------------------------------------------------------

    fun createOffer(sessionId: String) = runOnRtcThread {
        try {
            val peerConnection = createPeerConnection(sessionId)
            val videoSenders = mutableListOf<RtpSender>()

            localVideoTrack?.let { track ->
                peerConnection.addTrack(track, listOf("stream_0"))?.also { videoSenders.add(it) }
            }
            localAudioTrack?.let { track ->
                peerConnection.addTrack(track, listOf("stream_0"))
            }

            setVideoBitrate(videoSenders)

            val mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    runOnRtcThread {
                        peerConnection.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                signalingSender(
                                    sessionId,
                                    "offer",
                                    mapOf(
                                        "sdp" to sdp.description,
                                        "type" to sdp.type.canonicalForm()
                                    )
                                )
                            }

                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "createOffer setLocalDescription failed for $sessionId: $error")
                            }
                        }, sdp)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "createOffer failed for $sessionId: $error")
                }
            }, mediaConstraints)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating offer for $sessionId", e)
        }
    }

    /** 主动触发 ICE Restart：生成新的本地 ufrag/pwd 并重新发起 Offer。 */
    fun restartIce(sessionId: String) = runOnRtcThread {
        val peerConnection = peerConnections[sessionId] ?: run {
            Log.w(TAG, "restartIce: no PeerConnection for $sessionId")
            return@runOnRtcThread
        }

        val now = System.currentTimeMillis()
        val last = iceRestartAt.getOrDefault(sessionId, 0L)
        if (now - last < ICE_RESTART_INTERVAL_MS) {
            Log.i(TAG, "restartIce: throttled for $sessionId (last=$last)")
            return@runOnRtcThread
        }
        iceRestartAt[sessionId] = now

        Log.i(TAG, "restartIce: creating new offer for $sessionId")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                runOnRtcThread {
                    peerConnection.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            signalingSender(
                                sessionId,
                                "offer",
                                mapOf(
                                    "sdp" to sdp.description,
                                    "type" to sdp.type.canonicalForm()
                                )
                            )
                        }

                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "restartIce setLocalDescription failed for $sessionId: $error")
                        }
                    }, sdp)
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "restartIce createOffer failed for $sessionId: $error")
            }
        }, constraints)
    }

    fun setRemoteDescription(sessionId: String, sdpDescription: String, type: String) = runOnRtcThread {
        val peerConnection = peerConnections[sessionId] ?: run {
            Log.w(TAG, "setRemoteDescription: no PeerConnection for $sessionId")
            return@runOnRtcThread
        }

        val sdpType = when (type) {
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            else -> {
                Log.e(TAG, "setRemoteDescription: invalid SDP type '$type' for $sessionId, ignored")
                return@runOnRtcThread
            }
        }

        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set for $sessionId (type=$type)")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed for $sessionId: $error")
            }
        }, SessionDescription(sdpType, sdpDescription))
    }

    fun addIceCandidate(sessionId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) = runOnRtcThread {
        peerConnections[sessionId]?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun removePeerConnection(sessionId: String) = runOnRtcThread {
        peerConnections.remove(sessionId)?.close()
        iceRestartAt.remove(sessionId)
    }

    fun closeAll() = runOnRtcThread {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        iceRestartAt.clear()
    }

    /**
     * 按规范顺序释放：closeAll PeerConnection → dispose factory → release EglBase。
     * 注：videoCapturer / surfaceTextureHelper 由 ScreenShareService 在自己的
     * stopScreenShare 中先行释放，避免在已销毁 VideoSource 上继续回调帧。
     */
    fun release() = runOnRtcThread {
        try {
            closeAll()
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing peer connections", t)
        }
        try {
            _peerConnectionFactory?.dispose()
        } catch (t: Throwable) {
            Log.e(TAG, "Error disposing peerConnectionFactory", t)
        }
        _peerConnectionFactory = null
        try {
            eglBase?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing eglBase", t)
        }
        eglBase = null
        localVideoTrack = null
        localAudioTrack = null
        Log.i(TAG, "WebRTC resources released")
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun createPeerConnection(sessionId: String): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                Log.d(TAG, "SignalingState for $sessionId: $signalingState")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "IceConnectionState for $sessionId: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        rtcHandler.postDelayed({
                            if (peerConnections.containsKey(sessionId)) {
                                Log.w(TAG, "IceConnectionState=DISCONNECTED for $sessionId -> restartIce")
                                restartIce(sessionId)
                            }
                        }, ICE_DISCONNECTED_GRACE_MS)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "IceConnectionState=FAILED for $sessionId -> restartIce")
                        restartIce(sessionId)
                    }
                    PeerConnection.IceConnectionState.CLOSED -> Unit
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "IceConnectionReceiving for $sessionId: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "IceGatheringState for $sessionId: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                signalingSender(
                    sessionId,
                    "ice-candidate",
                    mapOf(
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "candidate" to candidate.sdp
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "onIceCandidatesRemoved for $sessionId: ${candidates?.size}")
            }

            override fun onAddStream(stream: MediaStream?) {
                // 发送端不期望远端 stream；保持空实现
            }

            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(dataChannel: DataChannel?) = Unit

            override fun onRenegotiationNeeded() {
                Log.i(
                    TAG,
                    "onRenegotiationNeeded for $sessionId (ignored: media setup is done before offer)"
                )
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        }

        val factory = _peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory is null when creating PeerConnection")
        val peerConnection = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("createPeerConnection returned null for $sessionId (rtcConfig invalid or factory disposed)")

        peerConnections[sessionId] = peerConnection
        return peerConnection
    }

    private fun setVideoBitrate(senders: List<RtpSender>) {
        for (sender in senders) {
            val parameters = sender.parameters ?: continue
            if (parameters.encodings.isNullOrEmpty()) {
                Log.w(TAG, "No encodings found for video sender, skipping bitrate config")
                continue
            }

            for (encoding in parameters.encodings) {
                encoding.maxBitrateBps = VIDEO_MAX_BITRATE_BPS
                encoding.minBitrateBps = VIDEO_MIN_BITRATE_BPS
                encoding.maxFramerate = VIDEO_MAX_FRAMERATE
            }

            sender.parameters = parameters
            Log.d(
                TAG,
                "Video bitrate configured: min=${VIDEO_MIN_BITRATE_BPS / 1000}kbps, " +
                    "max=${VIDEO_MAX_BITRATE_BPS / 1000}kbps, " +
                    "framerate=${VIDEO_MAX_FRAMERATE}fps"
            )
        }
    }

    private fun runOnRtcThread(block: () -> Unit) {
        if (Thread.currentThread() === rtcThread) {
            block()
        } else {
            rtcHandler.post(block)
        }
    }
}

/** 只覆盖实际需要的回调，避免在每个调用处写五个空方法。 */
abstract class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
