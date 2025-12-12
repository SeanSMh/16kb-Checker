package com.check16k.gradle

import com.check16k.core.HashOriginEntry
import com.check16k.core.HashOriginIndex
import com.check16k.core.Hashing
import com.check16k.core.Origin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.inject.Inject

abstract class CollectSoOriginsTask @Inject constructor() : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aarFiles: ConfigurableFileCollection

    /**
     * Map of absolute aar file path -> GAV (group:name:version) for Maven origins.
     */
    @get:Input
    abstract val aarOrigins: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniLibDirs: ConfigurableFileCollection

    @get:Input
    abstract val projectPath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    private val json = Json { prettyPrint = true }
    private val aarPattern = Regex("^jni/([^/]+)/(.+\\.so)$")

    @TaskAction
    fun run() {
        val entries = mutableListOf<HashOriginEntry>()
        val pathToOrigin = aarOrigins.get()

        aarFiles.files.forEach { file ->
            val origin = parseOrigin(pathToOrigin[file.absolutePath])
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .forEach { entry ->
                        val match = aarPattern.matchEntire(entry.name) ?: return@forEach
                        val abi = match.groupValues[1]
                        val soName = match.groupValues[2]
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val sha = Hashing.sha256(bytes)
                        entries += HashOriginEntry(
                            sha256 = sha,
                            abi = abi,
                            soName = soName,
                            origin = origin
                        )
                    }
            }
        }

        jniLibDirs.files.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "so" }
                .forEach { file ->
                    val bytes = file.readBytes()
                    val sha = Hashing.sha256(bytes)
                    entries += HashOriginEntry(
                        sha256 = sha,
                        abi = file.parentFile?.name,
                        soName = file.name,
                        origin = Origin.Project(projectPath.get(), source = "jniLibs")
                    )
                }
        }

        val index = HashOriginIndex(entries)
        val output = outputFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, json.encodeToString(index))
    }

    private fun parseOrigin(gav: String?): Origin {
        if (gav == null) return Origin.Unknown
        if (gav.startsWith(":")) {
            return Origin.Project(gav, source = "dependency")
        }
        val parts = gav.split(":")
        return if (parts.size >= 3) {
            Origin.Maven(parts[0], parts[1], parts[2])
        } else {
            Origin.File(gav)
        }
    }
}
