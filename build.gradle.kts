plugins {
    kotlin("jvm") version "1.9.23" apply false
    kotlin("plugin.serialization") version "1.9.23" apply false
}

allprojects {
    group = "com.check16k"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + listOf("-Xjsr305=strict")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
}
