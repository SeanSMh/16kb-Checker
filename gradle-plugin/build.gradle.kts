plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    compileOnly(gradleApi())
    compileOnly("com.android.tools.build:gradle:8.4.1")

    testImplementation(kotlin("test"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

gradlePlugin {
    plugins {
        create("check16k") {
            id = "com.check16k.plugin"
            displayName = "Android 16KB Checker"
            implementationClass = "com.check16k.gradle.Check16kPlugin"
            description = "Checks APK/AAB native libraries for 16KB page-size alignment and traces origins"
        }
    }
}
