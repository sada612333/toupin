package com.lys.toupin.receiver.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

class WebRTCManager(private val context: Context) {
    private val TAG = "WebRTCManager"
    
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    private var isInitialized = false
    
    // 基于player.html的ICE服务器配置
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )
    
    // RTC配置（参考player.html）
    private val rtcConfiguration = PeerConnection.RTCConfiguration(iceServers).apply {
        iceCandidatePoolSize = 2
        bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
    
    interface WebRTCListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onLocalDescription(description: SessionDescription)
        fun onVideoStreamReceived(track: VideoTrack)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
        fun onError(error: String)
    }
    
    private var listener: WebRTCListener? = null
    
    fun setListener(listener: WebRTCListener) {
        this.listener = listener
    }
    
    fun initialize() {
        if (isInitialized) return
        
        try {
            Log.i(TAG, "🔧 初始化WebRTC...")
            
            // 初始化WebRTC
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
            
            eglBase = EglBase.create()
            
            val options = PeerConnectionFactory.Options().apply {
                // 禁用硬件编码器以减少兼容性问题
                disableEncryption = false
                disableNetworkMonitor = false
            }
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
                .createPeerConnectionFactory()
            
            isInitialized = true
            Log.i(TAG, "✅ WebRTC初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebRTC初始化失败: ${e.message}")
            listener?.onError("WebRTC初始化失败: ${e.message}")
        }
    }
    
    fun createPeerConnection() {
        if (!isInitialized) {
            Log.e(TAG, "WebRTC未初始化")
            return
        }
        
        if (peerConnection != null) {
            Log.w(TAG, "PeerConnection已存在，先关闭旧的连接")
            peerConnection?.close()
        }
        
        Log.i(TAG, "🔗 创建PeerConnection")
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfiguration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "📶 信令状态变化: $state")
                }
                
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.i(TAG, "🧊 ICE连接状态变化: $state")
                    listener?.onIceConnectionStateChanged(state)
                }
                
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "🧊 ICE收集状态: $state")
                }
                
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "🧊 生成ICE候选: ${candidate.sdpMid}:${candidate.sdpMLineIndex}")
                    listener?.onIceCandidate(candidate)
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "🧊 ICE连接接收状态: $receiving")
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                    Log.d(TAG, "🧊 ICE候选移除: ${candidates.size}个")
                }
                
                override fun onDataChannel(dataChannel: DataChannel) {
                    Log.d(TAG, "📡 数据通道创建")
                }
                
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.i(TAG, "🔗 连接状态变化: $newState")
                    listener?.onConnectionStateChanged(newState)
                }
                
                override fun onAddStream(stream: MediaStream) {
                    Log.i(TAG, "📹 媒体流添加: ${stream.id}")
                    // 处理媒体流（兼容旧API）
                }
                
                override fun onRemoveStream(stream: MediaStream) {
                    Log.i(TAG, "📹 媒体流移除: ${stream.id}")
                }
                
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "🔄 需要重新协商")
                }
                
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    Log.i(TAG, "📹 轨道添加，类型: ${receiver.track()?.kind()}")
                    
                    when (receiver.track()?.kind()) {
                        "video" -> {
                            val videoTrack = receiver.track() as VideoTrack
                            Log.i(TAG, "🎬 收到视频轨道: ${videoTrack.id()}")
                            listener?.onVideoStreamReceived(videoTrack)
                        }
                        "audio" -> {
                            Log.d(TAG, "🔊 收到音频轨道")
                        }
                        else -> {
                            Log.d(TAG, "📡 收到未知类型轨道")
                        }
                    }
                }
                
                override fun onTrack(transceiver: RtpTransceiver) {
                    Log.d(TAG, "📹 onTrack事件，方向: ${transceiver.direction}")
                }
                
                override fun onRemoveTrack(receiver: RtpReceiver) {
                    Log.d(TAG, "📹 轨道移除")
                }
                
                // 兼容方法，可能不需要在新的WebRTC版本中实现
                override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {
                    Log.d(TAG, "🧊 选择的ICE候选对变化")
                }
            }
        )
        
        if (peerConnection == null) {
            Log.e(TAG, "❌ PeerConnection创建失败")
            listener?.onError("PeerConnection创建失败")
        } else {
            Log.i(TAG, "✅ PeerConnection创建成功")
        }
    }
    
    // 处理收到的SDP Offer（参考player.html处理逻辑）
    fun handleOffer(sdp: String) {
        Log.i(TAG, "📨 处理SDP Offer")
        
        if (peerConnection == null) {
            Log.e(TAG, "❌ PeerConnection未创建，无法处理Offer")
            return
        }
        
        if (peerConnection?.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "⚠️ 当前非稳定状态，跳过Offer处理")
            return
        }
        
        // 设置远端描述（进入have-remote-offer状态）
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                Log.d(TAG, "🔧 SDP创建成功")
            }
            
            override fun onSetSuccess() {
                Log.i(TAG, "✅ 远程SDP设置成功，状态: ${peerConnection?.signalingState()}")
                
                // 创建Answer
                if (peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                    createAnswer()
                } else {
                    Log.e(TAG, "❌ 错误状态，无法创建Answer")
                }
            }
            
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "❌ SDP创建失败: $error")
                listener?.onError("SDP创建失败: $error")
            }
            
            override fun onSetFailure(error: String) {
                Log.e(TAG, "❌ SDP设置失败: $error")
                listener?.onError("SDP设置失败: $error")
            }
        }, sessionDescription)
    }
    
    // 创建Answer（参考player.html的createAnswer逻辑）
    private fun createAnswer() {
        Log.i(TAG, "📤 创建Answer")
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                Log.i(TAG, "✅ Answer创建成功")
                
                // 修复SDP（参考player.html的修复逻辑）
                val fixedSdp = description.description.replace("a=inactive", "a=recvonly")
                val fixedAnswer = SessionDescription(SessionDescription.Type.ANSWER, fixedSdp)
                
                // 设置本地描述
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.i(TAG, "✅ 本地Answer设置成功")
                        listener?.onLocalDescription(fixedAnswer)
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "❌ 本地SDP创建失败: $error")
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "❌ 本地SDP设置失败: $error")
                    }
                }, fixedAnswer)
            }
            
            override fun onSetSuccess() {}
            
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "❌ Answer创建失败: $error")
                listener?.onError("Answer创建失败: $error")
            }
            
            override fun onSetFailure(error: String) {
                Log.e(TAG, "❌ Answer设置失败: $error")
                listener?.onError("Answer设置失败: $error")
            }
        }, constraints)
    }
    
    // 处理ICE候选（参考player.html的handleIceCandidate逻辑）
    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        Log.d(TAG, "🧊 添加ICE候选: $sdpMid:$sdpMLineIndex")
        
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }
    
    // 清理资源
    fun dispose() {
        Log.i(TAG, "🧹 清理WebRTC资源")
        peerConnection?.close()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        isInitialized = false
    }
}