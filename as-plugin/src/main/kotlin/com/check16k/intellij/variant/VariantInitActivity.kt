package com.check16k.intellij.variant

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class VariantInitActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val svc = project.getService(VariantStateService::class.java)
        svc.installListenersOnce()
    }
}
