pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // 阿里云镜像（优先）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }


        // 腾讯云镜像（备选）
        maven { url = uri("https://mirrors.cloud.tencent.com/Android/maven2/") }

        // 添加社区维护的 WebRTC 仓库
        maven { url = uri("https://raw.githubusercontent.com/alexgreench/google-webrtc/master") }
    }
}

rootProject.name = "toupin"
include(":sender")
include(":receiver")
 