package com.lys.toupin.receiver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.*

@SuppressLint("ViewConstructor")
class VideoRendererLayout(context: Context) : FrameLayout(context) {
    private val eglBase: EglBase = EglBase.create()
    private var surfaceView: SurfaceViewRenderer? = null
    private var currentTrack: VideoTrack? = null
    
    init {
        setupSurfaceView()
    }
    
    private fun setupSurfaceView() {
        try {
            surfaceView = SurfaceViewRenderer(context).apply {
                // 初始化前检查EGL上下文
                if (eglBase.eglBaseContext == null) {
                    Log.e("VideoRendererLayout", "❌ EGL上下文为空，无法初始化SurfaceViewRenderer")
                    return@apply
                }
                
                init(eglBase.eglBaseContext, null)
                setEnableHardwareScaler(false)  // 禁用硬件缩放以减少内存占用
                setMirror(false)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setZOrderMediaOverlay(true)  // 设置为媒体覆盖层以减少内存使用
                
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            
            if (surfaceView != null) {
                addView(surfaceView)
            } else {
                Log.e("VideoRendererLayout", "❌ SurfaceViewRenderer初始化失败")
            }
        } catch (e: Exception) {
            Log.e("VideoRendererLayout", "❌ 设置SurfaceView失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // 处理视频帧尺寸变化
    private fun onFrameDimensionsChanged(width: Int, height: Int) {
        // 根据视频分辨率调整显示策略
        Log.d("VideoRendererLayout", "📏 视频帧尺寸变化: ${width}x${height}")
        
        // 设置合适的缩放策略
        surfaceView?.setScalingType(if (width > 1000 || height > 1000) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        })
    }
    
    fun setVideoTrack(track: VideoTrack?) {
        try {
            // 先移除旧的轨道
            currentTrack?.removeSink(surfaceView)
            
            currentTrack = track
            
            // 添加新轨道前检查surfaceView状态
            if (track != null && surfaceView != null) {
                track.addSink(surfaceView)
                visibility = VISIBLE
                Log.d("VideoRendererLayout", "✅ 设置视频轨道成功")
            } else {
                visibility = GONE
                Log.d("VideoRendererLayout", "🔄 移除视频轨道")
            }
        } catch (e: Exception) {
            Log.e("VideoRendererLayout", "❌ 设置视频轨道失败: ${e.message}")
            e.printStackTrace()
            // 发生错误时确保清理资源
            currentTrack = null
            if (visibility != GONE) {
                visibility = GONE
            }
        }
    }
    
    fun release() {
        currentTrack?.removeSink(surfaceView)
        currentTrack = null
        
        try {
            // 先停止渲染，再释放资源
            surfaceView?.clearImage()
            surfaceView?.release()
            surfaceView = null
        } catch (e: Exception) {
            Log.e("VideoRendererLayout", "❌ 释放视频渲染器失败: ${e.message}")
        }
        
        try {
            eglBase.release()
        } catch (e: Exception) {
            Log.e("VideoRendererLayout", "❌ 释放EGL基础失败: ${e.message}")
        }
    }
}

@Composable
fun VideoView(
    modifier: Modifier = Modifier,
    videoTrack: VideoTrack? = null
) {
    val context = LocalContext.current
    val videoLayout = remember { VideoRendererLayout(context) }
    
    // 使用LaunchedEffect来管理视频轨道的生命周期
    LaunchedEffect(videoTrack) {
        if (videoTrack != null) {
            Log.d("VideoView", "🎬 接收到新视频轨道，开始渲染")
            // 添加小延迟确保SurfaceView已准备好
            kotlinx.coroutines.delay(100L)
            videoLayout.setVideoTrack(videoTrack)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            Log.d("VideoView", "🔄 释放视频渲染资源")
            videoLayout.setVideoTrack(null)
            videoLayout.release()
        }
    }
    
    AndroidView(
        factory = { videoLayout },
        modifier = modifier
    ) {
        // 更新视图时的回调
        videoLayout.setVideoTrack(videoTrack)
    }
}