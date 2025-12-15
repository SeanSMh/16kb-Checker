package com.check16k.intellij

import com.check16k.core.ScanReport
import com.check16k.core.Severity
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import java.nio.file.Path

class Check16kToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout(8, 8))
        val tableModel = ReportTableModel()
        val table = JBTable(tableModel)
        val settings = Check16kSettingsState.getInstance(project)

        val reloadButton = JButton("Reload report").apply {
            addActionListener {
                loadReport(project, settings.state, tableModel)
            }
        }
        val pathLabel = JLabel().apply { foreground = java.awt.Color(0x9a, 0x9a, 0x9a) }

        val top = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 8)
            add(reloadButton)
            add(pathLabel)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)

        loadReport(project, settings.state, tableModel)?.let { reportPath ->
            pathLabel.text = "Report: $reportPath"
        } ?: run {
            pathLabel.text = "Report not found. Run check16k task first."
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun loadReport(project: Project, state: Check16kSettingsState.State, model: ReportTableModel): String? {
        val base = project.basePath ?: return null
        // 按目录下最新的 json 报告加载，避免依赖 variant 设置
        val reportDir = Path.of(base, state.reportDir)
        val reportPath = latestJson(reportDir) ?: return null
        val file = reportPath.toFile()
        if (!file.exists()) {
            return null
        }
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val report = json.decodeFromString<ScanReport>(file.readText())
            model.update(report)
            reportPath.toString()
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, "Failed to load report: ${t.message}", "16KB Checker")
            null
        }
    }

    private fun latestJson(dir: Path): Path? {
        return try {
            Files.list(dir)
                .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".json") }
                .max { a, b ->
                    val t1 = Files.getLastModifiedTime(a).toMillis()
                    val t2 = Files.getLastModifiedTime(b).toMillis()
                    t1.compareTo(t2)
                }
                .orElse(null)
        } catch (_: Exception) {
            null
        }
    }
}

private class ReportTableModel : DefaultTableModel() {
    private val columns = arrayOf("Path", "ABI", "Severity", "Issues", "SHA256")

    init {
        setColumnIdentifiers(columns)
    }

    fun update(report: ScanReport) {
        dataVector.removeAllElements()
        report.items.forEach { item ->
            val hasFail = item.issues.any { it.severity == Severity.FAIL }
            val severity = if (hasFail) "FAIL" else if (item.issues.isNotEmpty()) "WARN" else "OK"
            val issuesText = item.issues.joinToString("; ") { "${it.type}: ${it.detail}" }
            addRow(arrayOf(item.path, item.abi ?: "-", severity, issuesText, item.sha256))
        }
        fireTableDataChanged()
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
