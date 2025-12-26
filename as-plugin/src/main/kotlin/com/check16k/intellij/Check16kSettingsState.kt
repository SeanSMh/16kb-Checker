package com.check16k.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

@State(name = "Check16kSettings", storages = [Storage("check16k_settings.xml")])
class Check16kSettingsState : PersistentStateComponent<Check16kSettingsState.State> {

    companion object {
        fun getInstance(project: Project): Check16kSettingsState =
            project.getService(Check16kSettingsState::class.java)
    }

    data class State(
        var reportDir: String = "check-result",
        var htmlReport: Boolean = true,
        var artifactPath: String = "",
        var modulePath: String = "",
        var variantName: String = "",
        var abiFilter: String = "" // 为空表示全 ABI
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
