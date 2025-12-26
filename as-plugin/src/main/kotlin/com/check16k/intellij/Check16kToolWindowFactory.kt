package com.check16k.intellij

import com.intellij.execution.filters.TextConsoleBuilderImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingUtilities

class Check16kToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        // 创建 ConsoleView
        val console = TextConsoleBuilderImpl(project).console

        // 存到 Holder，让 RunCheckAction 拿到同一个 console
        CheckConsoleHolder.attach(project, console)

        // val panel = JPanel(BorderLayout())

        val textArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        // val scroll = JBScrollPane(textArea)
        // panel.add(scroll, BorderLayout.CENTER)

        // 保存到 Project 级别，RunCheckAction 里就能写日志
        Check16kLogBus.attach(project, textArea)

        val content = ContentFactory.getInstance().createContent(console.component, "16kb Checker", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 一个最简单的“日志总线”，给 Action / Runner 写日志用
 */
object Check16kLogBus {
    private val map = java.util.concurrent.ConcurrentHashMap<Project, JBTextArea>()

    fun attach(project: Project, area: JBTextArea) {
        map[project] = area
    }

    fun log(project: Project, msg: String) {
        val area = map[project] ?: return
        SwingUtilities.invokeLater {
            area.append(msg)
            if (!msg.endsWith("\n")) area.append("\n")
            area.caretPosition = area.document.length
        }
    }

    fun clear(project: Project) {
        val area = map[project] ?: return
        SwingUtilities.invokeLater { area.text = "" }
    }
}
