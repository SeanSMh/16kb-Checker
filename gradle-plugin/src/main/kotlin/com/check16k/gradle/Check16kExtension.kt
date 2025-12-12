package com.check16k.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.file.DirectoryProperty
import javax.inject.Inject

open class Check16kExtension @Inject constructor(objects: ObjectFactory) {
    val pageSize: Property<Int> = objects.property(Int::class.java).convention(16_384)
    val strict: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val checkZipAlignment: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val checkCompressed: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val compressedAsError: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val inferOrigin: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val reportDir: DirectoryProperty = objects.directoryProperty()
    val formats: SetProperty<String> = objects.setProperty(String::class.java).convention(setOf("json"))
    val artifactType: Property<ArtifactTypePreference> =
        objects.property(ArtifactTypePreference::class.java).convention(ArtifactTypePreference.AUTO)
}

enum class ArtifactTypePreference {
    AUTO, // prefer APK if present, else BUNDLE
    APK,
    BUNDLE
}
