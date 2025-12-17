package com.check16k.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JLabel
import java.awt.GridBagLayout
import java.awt.GridBagConstraints

class Check16kSettingsConfigurable(private val project: Project) : Configurable {
    private val settings: Check16kSettingsState = Check16kSettingsState.getInstance(project)
    private val reportDirField = JTextField()
    private val htmlCheck = javax.swing.JCheckBox("生成 HTML 报告", true)
    private val statusLabel = JLabel().apply { foreground = java.awt.Color(0x9a, 0x9a, 0x9a) }
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "16kb-check"

    override fun createComponent(): JComponent {
        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 0.0
            insets = java.awt.Insets(4, 4, 4, 4)
        }

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        p.add(JLabel("Report dir:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        reportDirField.text = settings.state.reportDir
        p.add(reportDirField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        val htmlPanel = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)
            add(htmlCheck)
            add(statusLabel)
        }
        p.add(htmlPanel, gbc)

        panel = p
        return p
    }

    override fun isModified(): Boolean {
        return reportDirField.text != settings.state.reportDir ||
            htmlCheck.isSelected != settings.state.htmlReport
    }

    override fun apply() {
        val current = settings.state
        current.reportDir = reportDirField.text.trim().ifEmpty { "check-result" }
        current.htmlReport = htmlCheck.isSelected
    }

    override fun reset() {
        reportDirField.text = settings.state.reportDir
        htmlCheck.isSelected = settings.state.htmlReport
    }

    override fun disposeUIResources() {
        panel = null
    }
}
