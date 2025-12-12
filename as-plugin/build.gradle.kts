plugins {
    id("org.jetbrains.intellij") version "1.16.0"
    kotlin("jvm")
}

intellij {
    version.set("2022.3")
    type.set("IC")
    plugins.set(listOf("android", "gradle"))
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("223")
        untilBuild.set("*")
        pluginDescription.set("16KB Checker: run check16k Gradle tasks and view reports (with origin attribution).")
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
