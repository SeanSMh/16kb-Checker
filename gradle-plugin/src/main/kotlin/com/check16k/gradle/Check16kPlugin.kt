package com.check16k.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

class Check16kPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("check16k", Check16kExtension::class.java)
        extension.reportDir.convention(project.layout.projectDirectory.dir("check-result"))

        project.plugins.withId("com.android.application") {
            configureAndroid(project, extension)
        }
        project.plugins.withId("com.android.library") {
            configureAndroid(project, extension)
        }
    }

    private fun configureAndroid(project: Project, extension: Check16kExtension) {
        val components = project.extensions.findByType(AndroidComponentsExtension::class.java) ?: return
        val baseExtension = project.extensions.findByType(BaseExtension::class.java)

        components.onVariants { variant ->
            val variantName = variant.name
            val capitalized = variantName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

            val runtimeConfig = project.configurations.getByName("${variantName}RuntimeClasspath")
            val artifactView = runtimeConfig.incoming.artifactView { view ->
                view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            }

            val aarOrigins = artifactView.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts.associate { artifact ->
                    val id = artifact.id.componentIdentifier
                    val origin = when (id) {
                        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                        is ProjectComponentIdentifier -> id.projectPath
                        else -> artifact.file.absolutePath
                    }
                    artifact.file.absolutePath to origin
                }
            }

            val jniDirs = resolveJniDirs(baseExtension, variantName, variant.buildType)

            val collectTask = project.tasks.register(
                "collectCheck16k$capitalized",
                CollectSoOriginsTask::class.java
            ) { task ->
                task.group = "verification"
                task.description = "Collect native library hashes for $variantName"
                task.aarFiles.from(artifactView.files)
                task.aarOrigins.set(aarOrigins)
                task.jniLibDirs.from(jniDirs)
                task.projectPath.set(project.path)
                task.outputFile.set(
                    project.layout.buildDirectory.file("intermediates/check16k/$variantName/hash-origins.json")
                )
            }

            val reportFile = extension.reportDir.file("$variantName.json")
            val checkTask = project.tasks.register(
                "check16k$capitalized",
                Check16kTask::class.java
            ) { task ->
                task.group = "verification"
                task.description = "Check 16KB alignment for variant $variantName"
                task.variantName.set(variantName)
                task.pageSize.set(extension.pageSize)
                task.checkZipAlignment.set(extension.checkZipAlignment)
                task.checkCompressed.set(extension.checkCompressed)
                task.compressedAsError.set(extension.compressedAsError)
                task.inferOrigin.set(extension.inferOrigin)
                task.strict.set(extension.strict)
                task.strictSoNames.set(extension.strictSoNames)
                task.hashIndexFile.set(collectTask.flatMap { it.outputFile })
                task.reportFile.set(reportFile)

                val apkProvider = variant.artifacts.get(SingleArtifact.APK)
                val bundleProvider = variant.artifacts.get(SingleArtifact.BUNDLE)
                val preference = extension.artifactType.getOrElse(ArtifactTypePreference.AUTO)
                val (provider, taskName) = selectArtifact(preference, apkProvider, bundleProvider, capitalized, project)

                task.artifact.set(provider)
                task.dependsOn(collectTask)
                task.dependsOn(taskName)
            }

            project.tasks.named("check") {
                it.dependsOn(checkTask)
            }
        }
    }

    private fun selectArtifact(
        preference: ArtifactTypePreference,
        apkProvider: Provider<Directory>,
        bundleProvider: Provider<RegularFile>,
        capitalized: String,
        project: Project
    ): Pair<Provider<RegularFile>, String> {
        val apkFileProvider = project.layout.file(apkProvider.map { dir -> resolveApkFile(dir) })
        return when (preference) {
            ArtifactTypePreference.APK -> {
                if (apkProvider.isPresent) {
                    apkFileProvider to "package$capitalized"
                } else {
                    project.logger.warn("Requested APK but not present; falling back to bundle for $capitalized")
                    bundleProvider to "bundle$capitalized"
                }
            }
            ArtifactTypePreference.BUNDLE -> {
                if (bundleProvider.isPresent) {
                    bundleProvider to "bundle$capitalized"
                } else {
                    project.logger.warn("Requested BUNDLE but not present; falling back to apk for $capitalized")
                    apkFileProvider to "package$capitalized"
                }
            }
            ArtifactTypePreference.AUTO -> {
                if (apkProvider.isPresent) {
                    apkFileProvider to "package$capitalized"
                } else {
                    bundleProvider to "bundle$capitalized"
                }
            }
        }
    }

    private fun resolveApkFile(dir: Directory): File {
        val apkFiles = dir.asFileTree.files.filter { it.name.endsWith(".apk") }
        return apkFiles.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("No APK files found under ${dir.asFile}")
    }

    private fun resolveJniDirs(baseExtension: BaseExtension?, variantName: String, buildType: String?): List<File> {
        if (baseExtension == null) return emptyList()
        val sourceSets = baseExtension.sourceSets
        val names = linkedSetOf("main", variantName)
        if (!buildType.isNullOrBlank()) {
            names += buildType
        }

        return names.flatMap { name ->
            sourceSets.findByName(name)?.jniLibs?.srcDirs ?: emptySet()
        }.filter { it.exists() }.distinct()
    }
}
