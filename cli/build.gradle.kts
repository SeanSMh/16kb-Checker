plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.check16k.cli.MainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
