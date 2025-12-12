pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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
