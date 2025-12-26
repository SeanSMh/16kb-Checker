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

    plugins.set(listOf("java", "org.jetbrains.plugins.gradle", "android"))
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
        untilBuild.set("999.*")
        pluginDescription.set(
            """
            <p><b>Slogan:</b> 16KB 一键体检，原生库对齐合格才放心。</p>
            <p><b>16kb Checker</b> helps Android projects verify whether APK/AAB native libraries meet the 16KB page alignment requirement.</p>
            <p><b>Compatibility:</b> Android Studio based on IntelliJ Platform build <b>231+</b> (typically Flamingo+).</p>
            <p><b>What it does</b></p>
            <ul>
              <li>Scan an APK/AAB and generate a report (JSON / optional HTML).</li>
              <li>Optionally run a Gradle task to collect SO origin attribution and enrich the report.</li>
              <li>Support manual artifact path input; if empty, auto-detect the latest APK/AAB.</li>
              <li>Show logs in a dedicated ToolWindow; fallback to idea.log if the console is not ready.</li>
            </ul>
            <p><b>How to use</b></p>
            <ol>
              <li>Open <i>Tools</i> menu and run <b>16kb Check</b>.</li>
              <li>Select module / variant / ABI and (optionally) provide an APK/AAB path.</li>
              <li>View results in the ToolWindow and the output report directory.</li>
            </ol>
            """.trimIndent()
        )
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
