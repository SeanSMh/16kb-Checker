package com.check16k.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants

class RunCheckAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = Check16kSettingsState.getInstance(project).state
        val variant = settings.variant
        val taskName = "check16k${variant.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"

        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            Messages.showErrorDialog(project, "Project base path not found.", "16KB Checker")
            return
        }

        val execSettings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = basePath
            taskNames = listOf(taskName)
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            isPassParentEnvs = true
        }

        ExternalSystemUtil.runTask(
            execSettings,
            GradleConstants.SYSTEM_ID,
            project,
            null,
            null,
            false
        )
    }
}
