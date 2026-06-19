# WebRTC native bridge：保持 JNI 入口类、反射使用的类名不变
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# 信令库 Java-WebSocket：WebSocketListener / Draft_6455 等内部类需要被反射
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# 保留用于 Gson 反序列化的注解字段（否则 R8 可能抹掉 @SerializedName 的名字）
-keepattributes Signature, *Annotation*, RuntimeVisibleAnnotations
