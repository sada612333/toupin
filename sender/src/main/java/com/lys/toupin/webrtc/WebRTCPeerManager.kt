package com.lys.toupin.webrtc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

class WebRTCPeerManager(
    private val context: Context,
    private val signalingSender: (sessionId: String, type: String, payload: Map<String, Any>) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCPeerManager"
    }

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val gson = Gson()
    private var _peerConnectionFactory: PeerConnectionFactory? = null
    val peerConnectionFactory: PeerConnectionFactory get() = _peerConnectionFactory!!
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun initialize() {
        val eglBase = EglBase.create()
        val peerConnectionFactoryOptions = PeerConnectionFactory.Options()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        _peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(peerConnectionFactoryOptions)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun setVideoSource(videoTrack: VideoTrack) {
        this.localVideoTrack = videoTrack
    }

    fun setAudioSource(audioTrack: AudioTrack) {
        this.localAudioTrack = audioTrack
    }

    fun createOffer(sessionId: String) {
        try {
            val peerConnection = createPeerConnection(sessionId)
            localVideoTrack?.let { track ->
                peerConnection.addTrack(track, listOf("stream_0"))
            }
            localAudioTrack?.let { track ->
                peerConnection.addTrack(track, listOf("stream_0"))
            }

            val mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    Log.d(TAG, "Offer created for $sessionId")
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription) {}
                        override fun onSetSuccess() {
                            signalingSender(
                                sessionId,
                                "offer",
                                mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())
                            )
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "Failed to create offer for $sessionId")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, mediaConstraints)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating offer for $sessionId", e)
        }
    }

    fun setRemoteDescription(sessionId: String, sdpDescription: String, type: String) {
        peerConnections[sessionId]?.let { peerConnection ->
            val sdpType = when (type) {
                "answer" -> SessionDescription.Type.ANSWER
                else -> SessionDescription.Type.OFFER
            }
            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set for $sessionId")
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "Failed to set remote description for $sessionId")
                }
            }, SessionDescription(sdpType, sdpDescription))
        }
    }

    fun addIceCandidate(sessionId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnections[sessionId]?.let { peerConnection ->
            peerConnection.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }
    }

    fun removePeerConnection(sessionId: String) {
        peerConnections.remove(sessionId)?.close()
    }

    fun closeAll() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
    }

    fun release() {
        closeAll()
        _peerConnectionFactory?.dispose()
        _peerConnectionFactory = null
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
    }

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
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState for $sessionId: $iceConnectionState")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    signalingSender(
                        sessionId,
                        "ice-candidate",
                        mapOf(
                            "sdpMid" to it.sdpMid,
                            "sdpMLineIndex" to it.sdpMLineIndex,
                            "candidate" to it.sdp
                        )
                    )
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }

        return _peerConnectionFactory!!.createPeerConnection(rtcConfig, observer)!!.also {
            peerConnections[sessionId] = it
        }
    }
}
