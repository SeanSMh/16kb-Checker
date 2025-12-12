plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
