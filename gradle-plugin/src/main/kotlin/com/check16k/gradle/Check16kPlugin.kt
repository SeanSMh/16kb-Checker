package com.check16k.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.FileComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
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
                        is FileComponentIdentifier -> artifact.file.absolutePath
                        else -> id.displayName
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
                task.aarFiles.from(artifactView.artifactFiles)
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
                task.hashIndexFile.set(collectTask.flatMap { it.outputFile })
                task.reportFile.set(reportFile)

                val apkProvider = variant.artifacts.get(SingleArtifact.APK)
                val bundleProvider = variant.artifacts.get(SingleArtifact.BUNDLE)
                val artifactProvider = if (apkProvider.isPresent) apkProvider else bundleProvider
                task.artifact.set(artifactProvider)
                task.dependsOn(collectTask)
                val packageTask = if (apkProvider.isPresent) "package$capitalized" else "bundle$capitalized"
                task.dependsOn(packageTask)
            }

            project.tasks.named("check") {
                it.dependsOn(checkTask)
            }
        }
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
