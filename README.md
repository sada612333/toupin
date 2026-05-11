# 安卓投屏项目

## 项目简介
基于 Jetpack Compose + WebRTC 的安卓投屏工具，分为发送端（手机/平板）和接收端（电视/盒子），支持局域网内实时音视频传输。

## 技术栈
- **语言**: Kotlin
- **UI框架**: Jetpack Compose
- **传输技术**: WebRTC
- **信令通信**: WebSocket
- **最低版本**: Android 8.0 (API 26)

## 快速导航
- [项目核心概述](doc/项目核心概述文档_v1.0.md)
- [项目问题清单](doc/项目问题清单文档_v1.0.md)
- [项目整体架构](doc/项目整体架构文档_v1.0.md)
- [里程碑与节奏](doc/项目里程碑与节奏文档_v1.0.md)

## 模块文档
- [发送端模块](doc/子模块-发送端模块文档_v1.0.md)
- [接收端模块](doc/子模块-接收端模块文档_v1.0.md)
- [连接细节](doc/补充文档-连接细节文档_v1.0.md)
- [性能优化与监控](doc/补充文档-性能优化与监控文档_v1.0.md)

## 辅助文档
- [项目重构模块](doc/项目重构模块文档_v1.0.md)
- [测试模块](doc/测试模块文档_v1.0.md)
- [约束条件](doc/项目约束条件文档_v1.0.md)
- [术语定义](doc/术语与定义文档_v1.0.md)
- [风险与应对](doc/风险与应对文档_v1.0.md)
- [参考资料](doc/参考资料文档_v1.0.md)

## 项目结构
```
toupin/
├── sender/                    # 发送端模块（手机/平板）
│   ├── src/main/java/com/lys/toupin/
│   │   ├── MainActivity.kt            # 主界面
│   │   ├── ScreenShareService.kt      # 屏幕共享服务
│   │   ├── ScreenShareViewModel.kt    # 视图模型
│   │   ├── WebRTCPeerManager.kt       # WebRTC连接管理
│   │   ├── WebSocketSignalingServer.kt # 信令服务器
│   │   └── SignalingConfig.kt         # 信令配置
│   └── build.gradle.kts

├── receiver/                  # 接收端模块（电视/盒子）
│   ├── src/main/java/com/lys/toupin/receiver/
│   │   ├── MainActivity.kt            # 主界面
│   │   ├── viewmodel/ReceiverViewModel.kt  # 视图模型
│   │   ├── ui/VideoView.kt            # 视频渲染组件
│   │   ├── websocket/WebSocketClient.kt   # WebSocket客户端
│   │   └── webrtc/WebRTCManager.kt    # WebRTC管理
│   └── build.gradle.kts

├── doc/                       # 项目文档
│   ├── 项目核心概述文档_v1.0.md
│   ├── 项目问题清单文档_v1.0.md
│   └── ...

├── gradle/                    # Gradle配置
├── build.gradle.kts           # 项目级构建配置
├── gradle.properties          # Gradle属性
└── settings.gradle.kts        # 项目设置
```

## 快速开始

### 环境要求
- Android Studio Hedgehog | 2023.1.1+
- Gradle 8.5+
- Kotlin 1.9+

### 运行发送端
1. 打开 `sender` 模块
2. 配置运行配置，选择 `app` 模块
3. 连接安卓设备或启动模拟器
4. 点击运行

### 运行接收端
1. 打开 `receiver` 模块
2. 配置运行配置，选择 `app` 模块
3. 连接安卓电视/盒子或启动大屏模拟器
4. 输入发送端IP地址，点击"开始监听"

## 核心功能
- ✅ 屏幕内容采集（MediaProjection）
- ✅ WebRTC实时音视频传输
- ✅ WebSocket信令通信
- ✅ 悬浮窗预览（发送端）
- ✅ 视频流接收与显示（接收端）

## 待优化项
- ⬜ 界面布局优化（多机型适配）
- ⬜ 连接方式扩展（热点、二维码）
- ⬜ 性能监控模块
- ⬜ 架构重构（代码去重）

## 编码规范
1. **单一功能点可验证**：每个功能点独立设计，支持单独测试
2. **日志规范**：统一日志级别、格式，包含时间戳、模块名、功能点
3. **依赖适配**：注意与 gradle、安卓gradle插件的版本兼容性

## 联系方式
如有问题，请参考项目问题清单文档或提交Issue。