package com.lys.toupin

import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * WebRTC对等连接管理类，支持UNIFIED_PLAN配置
 */
class WebRTCPeerManager {
    companion object {
        private const val TAG = "WebRTCPeerManager"
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    
    // WebRTC组件
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localPeerConnection: PeerConnection? = null
    private var remotePeerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    
    // 媒体流
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    var currentSessionDescription: SessionDescription? = null
    
    // 回调接口
    interface PeerManagerListener {
        fun onLocalDescription(sessionDescription: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onError(error: String)
    }
    
    private var peerManagerListener: PeerManagerListener? = null
    
    /**
     * 初始化WebRTC组件
     */
    fun initialize(context: android.content.Context, onInitialized: (() -> Unit)? = null) {
        Log.d(TAG, "Initializing WebRTC Peer Manager")
        
        executor.execute {
            try {
                // 初始化EGL
                rootEglBase = EglBase.create()
                
                // 初始化PeerConnectionFactory
                val initializationOptions = PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(true)
                    .setFieldTrials("WebRTC-UnifiedPlan/Enabled/")
                    .createInitializationOptions()
                
                PeerConnectionFactory.initialize(initializationOptions)
                
                // 创建PeerConnectionFactory
                val encoderFactory = DefaultVideoEncoderFactory(
                    rootEglBase?.eglBaseContext,
                    true,  // enableIntelVp8Encoder
                    true   // enableH264HighProfile
                )
                
                val decoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)
                
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
                
                Log.d(TAG, "WebRTC Peer Manager initialized successfully")
                onInitialized?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebRTC Peer Manager: ${e.message}")
                peerManagerListener?.onError("Initialization failed: ${e.message}")
            }
        }
    }
    
    /**
     * 创建本地对等连接（发送方）
     */
    fun createLocalPeerConnection(videoSource: VideoSource, videoTrack: VideoTrack) {
        this.videoSource = videoSource
        this.videoTrack = videoTrack
        
        executor.execute {
            try {
                // 配置 ICE 服务器（可根据需要添加STUN/TURN服务器）
                val iceServers = mutableListOf<PeerConnection.IceServer>().apply {
                    add(PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer())
                    add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
                    add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                    add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
                    add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
                }
                
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    // 启用UNIFIED_PLAN
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    // 其他配置
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    // 启用更强的加密
                    keyType = PeerConnection.KeyType.ECDSA
                    // 设置候选收集策略
                    iceCandidatePoolSize = 2
                }
                
                // 创建对等连接
                localPeerConnection = peerConnectionFactory?.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState) {
                            Log.d(TAG, "Local PeerConnection signaling state: $state")
                        }
                        
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                            Log.d(TAG, "Local PeerConnection ICE connection state: $state")
                            val connectionState = when (state) {
                                PeerConnection.IceConnectionState.NEW -> PeerConnection.PeerConnectionState.NEW
                                PeerConnection.IceConnectionState.CHECKING -> PeerConnection.PeerConnectionState.CONNECTING
                                PeerConnection.IceConnectionState.CONNECTED -> PeerConnection.PeerConnectionState.CONNECTED
                                PeerConnection.IceConnectionState.COMPLETED -> PeerConnection.PeerConnectionState.CONNECTED
                                PeerConnection.IceConnectionState.FAILED -> PeerConnection.PeerConnectionState.FAILED
                                PeerConnection.IceConnectionState.DISCONNECTED -> PeerConnection.PeerConnectionState.DISCONNECTED
                                PeerConnection.IceConnectionState.CLOSED -> PeerConnection.PeerConnectionState.CLOSED
                                else -> PeerConnection.PeerConnectionState.NEW
                            }
                            peerManagerListener?.onConnectionChange(connectionState)
                        }
                        
                        override fun onIceConnectionReceivingChange(receiving: Boolean) {
                            Log.d(TAG, "Local PeerConnection ICE receiving: $receiving")
                        }
                        
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            Log.d(TAG, "Local PeerConnection ICE gathering state: $state")
                        }
                        
                        override fun onIceCandidate(candidate: IceCandidate) {
                            Log.d(TAG, "Local PeerConnection ICE candidate: $candidate")
                            peerManagerListener?.onIceCandidate(candidate)
                        }
                        
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                            Log.d(TAG, "Local PeerConnection ICE candidates removed: ${candidates.size}")
                        }
                        
                        override fun onAddStream(stream: MediaStream) {
                            Log.d(TAG, "Local PeerConnection stream added: ${stream.id}")
                        }
                        
                        override fun onRemoveStream(stream: MediaStream) {
                            Log.d(TAG, "Local PeerConnection stream removed: ${stream.id}")
                        }
                        
                        override fun onDataChannel(dataChannel: DataChannel) {
                            Log.d(TAG, "Local PeerConnection data channel: ${dataChannel.label()}")
                        }
                        
                        override fun onRenegotiationNeeded() {
                            Log.d(TAG, "Local PeerConnection renegotiation needed")
                            // 注意：暂时禁用自动重协商，避免循环创建offer
                            // createOffer()  // 注释掉避免循环调用
                        }
                        
                        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                            Log.d(TAG, "Local PeerConnection track added: ${receiver.id()}")
                        }
                    }
                )
                
                if (localPeerConnection != null) {
                    // UNIFIED_PLAN语义下直接添加轨道，而不是添加整个流
                    localPeerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
                    
                    Log.d(TAG, "Local PeerConnection created successfully")
                    
                    // 创建SDP Offer
                    createOffer()
                } else {
                    Log.e(TAG, "Failed to create local PeerConnection")
                    peerManagerListener?.onError("Failed to create local PeerConnection")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating local PeerConnection: ${e.message}")
                peerManagerListener?.onError("PeerConnection creation failed: ${e.message}")
            }
        }
    }
    /**
     * 创建SDP Offer
     */
    fun createOffer() {
        executor.execute {
            try {
                val sdpConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }
                
                localPeerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        Log.d(TAG, "Offer created successfully: ${description.type}")
                        
                        // 设置本地描述
                        localPeerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local description set successfully")
                                currentSessionDescription = description
                                peerManagerListener?.onLocalDescription(description)
                            }
                            override fun onCreateFailure(error: String) {
                                Log.e(TAG, "Create local description failed: $error")
                            }
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "Set local description failed: $error")
                            }
                        }, description)
                    }
                    
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Create offer failed: $error")
                        peerManagerListener?.onError("Offer creation failed: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Set offer failed: $error")
                    }
                }, sdpConstraints)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating offer: ${e.message}")
                peerManagerListener?.onError("Offer creation error: ${e.message}")
            }
        }
    }
    
    /**
     * 设置远程SDP描述
     */
    fun setRemoteDescription(description: SessionDescription) {
        executor.execute {
            try {
                localPeerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote description set successfully")
                        // 如果是offer，创建answer
                        if (description.type == SessionDescription.Type.OFFER) {
                            createAnswer()
                        }
                    }
                    override fun onCreateSuccess(desc: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Set remote description failed: $error")
                        peerManagerListener?.onError("Set remote description failed: $error")
                    }
                }, description)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting remote description: ${e.message}")
                peerManagerListener?.onError("Set remote description error: ${e.message}")
            }
        }
    }
    
    /**
     * 创建SDP Answer
     */
    private fun createAnswer() {
        executor.execute {
            try {
                val sdpConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }
                
                localPeerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        Log.d(TAG, "Answer created successfully: ${description.type}")
                        
                        // 设置本地描述
                        localPeerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local answer description set successfully")
                                currentSessionDescription = description
                                peerManagerListener?.onLocalDescription(description)
                            }
                            override fun onCreateFailure(error: String) {}
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "Set local answer description failed: $error")
                            }
                        }, description)
                    }
                    
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Create answer failed: $error")
                        peerManagerListener?.onError("Answer creation failed: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Set answer failed: $error")
                    }
                }, sdpConstraints)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating answer: ${e.message}")
                peerManagerListener?.onError("Answer creation error: ${e.message}")
            }
        }
    }
    
    /**
     * 添加ICE候选
     */
    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            try {
                localPeerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "ICE candidate added: ${candidate.sdp}")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding ICE candidate: ${e.message}")
            }
        }
    }
    
    /**
     * 设置PeerManager监听器
     */
    fun setPeerManagerListener(listener: PeerManagerListener) {
        this.peerManagerListener = listener
    }
    
    /**
     * 释放资源
     */
    fun dispose() {
        executor.execute {
            try {
                localPeerConnection?.close()
                remotePeerConnection?.close()
                videoSource?.dispose()
                videoTrack?.dispose()
                peerConnectionFactory?.dispose()
                rootEglBase?.release()
                
                localPeerConnection = null
                remotePeerConnection = null
                videoSource = null
                videoTrack = null
                peerConnectionFactory = null
                rootEglBase = null
                
                Log.d(TAG, "WebRTC Peer Manager disposed")
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing WebRTC Peer Manager: ${e.message}")
            }
        }
    }
}