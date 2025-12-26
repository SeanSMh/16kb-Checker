package com.check16k.intellij

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * 方案B：ToolWindow 里创建 ConsoleView 后，保存到这里，供 RunCheckAction 实时写日志。
 */
object CheckConsoleHolder {
    private val consoles = ConcurrentHashMap<Project, ConsoleView>()

    fun attach(project: Project, console: ConsoleView) {
        consoles[project] = console
        Disposer.register(project, Disposable { detach(project) })
    }

    fun get(project: Project): ConsoleView? = consoles[project]

    fun detach(project: Project) {
        consoles.remove(project)
    }
}
