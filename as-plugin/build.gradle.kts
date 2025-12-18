plugins {
    id("org.jetbrains.intellij") version "1.16.0"
    kotlin("jvm")
}

repositories {
    google()
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-repository/releases")
}

intellij {
    // 使用 IntelliJ IDEA 2023.1 平台（231.*），包含 Gradle/External System
    version.set("2023.1.5")
    type.set("IC")
    pluginName.set("16kb-check")
    // 使用明确的插件 ID，保证 Gradle External System API 进入编译类路径
    plugins.set(listOf("java", "org.jetbrains.plugins.gradle"))
    downloadSources.set(false)
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
}

tasks {
    patchPluginXml {
        // 兼容 231+（Android Studio 基于 231，亦兼容更高版本）
        sinceBuild.set("231")
        untilBuild.set("252.*")
        pluginDescription.set("16kb-check: run check16k Gradle tasks and view reports (with origin attribution).")
    }
    publishPlugin {
        // Configure plugin publishing if needed.
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
