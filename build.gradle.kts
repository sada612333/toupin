plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// 为所有子项目注入占位的 prepareKotlinBuildScriptModel，以避免 Android Studio / Idea
// 在 Tooling API 请求"构建脚本模型"时因任务不存在而报错。真正任务的实际逻辑由
// Kotlin Gradle Plugin 负责在请求上下文里注入，这里提供的占位实现确保任务至少存在。
subprojects {
    afterEvaluate {
        if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
            tasks.register("prepareKotlinBuildScriptModel")
        }
    }
}
