package com.check16k.intellij

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
    private val variantField = JTextField()
    private val reportDirField = JTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "16KB Checker"

    override fun createComponent(): JComponent {
        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 0.0
            insets = java.awt.Insets(4, 4, 4, 4)
        }

        p.add(JLabel("Variant:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        variantField.text = settings.state.variant
        p.add(variantField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        p.add(JLabel("Report dir:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        reportDirField.text = settings.state.reportDir
        p.add(reportDirField, gbc)

        panel = p
        return p
    }

    override fun isModified(): Boolean {
        return variantField.text != settings.state.variant ||
            reportDirField.text != settings.state.reportDir
    }

    override fun apply() {
        settings.state = settings.state.copy(
            variant = variantField.text.trim().ifEmpty { "Release" },
            reportDir = reportDirField.text.trim().ifEmpty { "check-result" }
        )
    }

    override fun reset() {
        variantField.text = settings.state.variant
        reportDirField.text = settings.state.reportDir
    }

    override fun disposeUIResources() {
        panel = null
    }
}
