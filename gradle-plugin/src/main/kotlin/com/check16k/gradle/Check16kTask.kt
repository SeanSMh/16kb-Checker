package com.check16k.gradle

import com.check16k.core.ArchiveScanner
import com.check16k.core.CheckConfig
import com.check16k.core.HashOriginIndex
import com.check16k.core.ReportWriter
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

abstract class Check16kTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifact: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val pageSize: Property<Int>

    @get:Input
    abstract val checkZipAlignment: Property<Boolean>

    @get:Input
    abstract val checkCompressed: Property<Boolean>

    @get:Input
    abstract val compressedAsError: Property<Boolean>

    @get:Input
    abstract val inferOrigin: Property<Boolean>

    @get:Input
    abstract val strict: Property<Boolean>

    @get:Input
    abstract val strictSoNames: SetProperty<String>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hashIndexFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private val json = Json { ignoreUnknownKeys = true }

    @TaskAction
    fun runCheck() {
        val config = CheckConfig(
            pageSize = pageSize.get(),
            checkZipAlignment = checkZipAlignment.get(),
            checkCompressed = checkCompressed.get(),
            compressedAsError = compressedAsError.get(),
            inferOrigin = inferOrigin.get(),
            strict = strict.get(),
            strictSoNames = strictSoNames.get()
        )

        val hashMap = if (inferOrigin.get()) {
            loadHashIndex()
        } else {
            emptyMap()
        }

        val scanner = ArchiveScanner(config)
        val report = scanner.scan(
            artifact = artifact.get().asFile.toPath(),
            variant = variantName.orNull,
            hashOrigins = hashMap
        )

        val output = reportFile.get().asFile.toPath()
        ReportWriter.writeJson(report, output)

        if (strict.get() && report.summary.fail > 0) {
            throw GradleException("check16k found ${report.summary.fail} failing .so files for variant ${variantName.get()}")
        }
    }

    private fun loadHashIndex(): Map<String, List<com.check16k.core.Origin>> {
        val file = hashIndexFile.orNull?.asFile ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        val content = Files.readString(file.toPath())
        val index = json.decodeFromString<HashOriginIndex>(content)
        return index.toMap()
    }
}
