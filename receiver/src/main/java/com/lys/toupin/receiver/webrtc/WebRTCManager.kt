package com.lys.toupin.receiver.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(
    private val context: Context,
    private val signalingSender: (type: String, payload: Map<String, Any>) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
    }

    private val eglBase by lazy { EglBase.create() }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (initialized.getAndSet(true)) return
        val options = PeerConnectionFactory.Options()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        this.renderer?.release()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setMirror(false)
        renderer.setZOrderMediaOverlay(false)
        this.renderer = renderer
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(renderer)
        try {
            renderer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing renderer", e)
        }
        if (this.renderer === renderer) {
            this.renderer = null
        }
    }

    fun createPeerConnection() {
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory is not initialized")
            return
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState: $iceConnectionState")
                iceConnectionState?.let { state ->
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            signalingSender("ice-connected", emptyMap())
                        }

                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            signalingSender("ice-failed", emptyMap())
                        }

                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "IceGatheringState: $state")
            }

            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                candidate?.let {
                    signalingSender(
                        "ice-candidate",
                        mapOf(
                            "sdpMid" to it.sdpMid,
                            "sdpMLineIndex" to it.sdpMLineIndex,
                            "candidate" to it.sdp
                        )
                    )
                }
            }

            override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "onAddStream: ${stream?.videoTracks?.size} video tracks")
                stream?.videoTracks?.firstOrNull()?.let { videoTrack ->
                    remoteVideoTrack = videoTrack
                    renderer?.let { videoTrack.addSink(it) }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let {
                    renderer?.let { r -> it.removeSink(r) }
                }
                if (remoteVideoTrack === stream?.videoTracks?.firstOrNull()) {
                    remoteVideoTrack = null
                }
            }

            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }

        peerConnection = factory.createPeerConnection(rtcConfig, observer)
    }

    fun handleOffer(sdp: String) {
        val pc = peerConnection ?: run {
            Log.e(TAG, "handleOffer: no peer connection")
            return
        }

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteDescription") {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set")
                createAnswer()
            }
        }, offer)
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return
        val constraints = org.webrtc.MediaConstraints()
        pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                Log.d(TAG, "Answer created")
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set")
                        signalingSender(
                            "answer",
                            mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())
                        )
                    }
                }, sdp)
            }
        }, constraints)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(
            org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
        )
    }

    fun close() {
        try {
            remoteVideoTrack?.let { track ->
                renderer?.let { track.removeSink(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing sink", e)
        }
        remoteVideoTrack = null
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing peer connection", e)
        }
        peerConnection = null
    }

    fun release() {
        close()
        try {
            renderer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing renderer", e)
        }
        renderer = null
        try {
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing factory", e)
        }
        peerConnectionFactory = null
        try {
            eglBase.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing eglBase", e)
        }
    }
}

abstract class SimpleSdpObserver(private val name: String) : org.webrtc.SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}

    override fun onSetSuccess() {}

    override fun onCreateFailure(error: String?) {
        Log.e("SimpleSdpObserver", "$name createFailure: $error")
    }

    override fun onSetFailure(error: String?) {
        Log.e("SimpleSdpObserver", "$name setFailure: $error")
    }
}
