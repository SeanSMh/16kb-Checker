package com.check16k.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Origin {
    @Serializable
    @SerialName("maven")
    data class Maven(
        val group: String,
        val name: String,
        val version: String
    ) : Origin() {
        val gav: String get() = "$group:$name:$version"
    }

    @Serializable
    @SerialName("project")
    data class Project(
        val path: String,
        val source: String = "jniLibs"
    ) : Origin()

    @Serializable
    @SerialName("file")
    data class File(
        val path: String
    ) : Origin()

    @Serializable
    @SerialName("unknown")
    data object Unknown : Origin()
}

@Serializable
data class OriginMatch(
    val origin: Origin,
    val confidence: Double = 1.0
)
