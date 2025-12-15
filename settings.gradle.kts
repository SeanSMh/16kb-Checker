pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 允许项目/插件在构建过程中按需添加仓库（如 Gradle IntelliJ Plugin 的 IntelliJ 平台仓库）
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-repository/releases")
    }
}

rootProject.name = "check16k"

include(":core")
include(":gradle-plugin")
include(":cli")
include(":as-plugin")
