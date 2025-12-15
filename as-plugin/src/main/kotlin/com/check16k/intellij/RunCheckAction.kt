package com.check16k.intellij

import com.check16k.core.ArchiveScanner
import com.check16k.core.CheckConfig
import com.check16k.core.ReportWriter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class RunCheckAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = Check16kSettingsState.getInstance(project).state
        val basePathStr = project.basePath
        if (basePathStr.isNullOrBlank()) {
            Messages.showErrorDialog(project, "Project base path not found.", "16KB Checker")
            return
        }
        val basePath = Path.of(basePathStr)

        notify(project, "开始扫描产物（APK/AAB）…", NotificationType.INFORMATION)

        object : Task.Backgroundable(project, "16KB Checker Scan", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "查找 APK/AAB…"
                val artifact = findLatestArtifact(basePath)
                if (artifact == null) {
                    notify(project, "未找到可用的 APK/AAB，请先构建产物。", NotificationType.WARNING)
                    return
                }

                indicator.text = "扫描 ${artifact.fileName}…"
                val scanner = ArchiveScanner(CheckConfig())
                val report = try {
                    val variant = artifact.fileName.toString().substringBeforeLast(".")
                    scanner.scan(artifact, variant, emptyMap())
                } catch (t: Throwable) {
                    notify(project, "扫描失败：${t.message}", NotificationType.ERROR)
                    return
                }

                val reportDir = basePath.resolve(settings.reportDir.ifBlank { "check-result" })
                val variantName = report.variant ?: artifact.fileName.toString().substringBeforeLast(".")
                val output = reportDir.resolve("$variantName.json")
                try {
                    ReportWriter.writeJson(report, output, pretty = true)
                    if (settings.htmlReport) {
                        val htmlOut = reportDir.resolve("$variantName.html")
                        ReportWriter.writeHtml(report, htmlOut)
                    }
                } catch (t: Throwable) {
                    notify(project, "写入报告失败：${t.message}", NotificationType.ERROR)
                    return
                }

                val severity = if (report.summary.fail > 0) NotificationType.WARNING else NotificationType.INFORMATION
                val summary = "扫描完成：total=${report.summary.totalSo}, fail=${report.summary.fail}, warn=${report.summary.warn}"
                val htmlNote = if (settings.htmlReport) "\nHTML 报告已保存到同目录。" else ""
                notify(project, "$summary\n报告已保存：$output$htmlNote", severity)

                // 刷新 VFS，确保 Android Studio 立即展示生成的文件
                LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(reportDir.toAbsolutePath().toString())
                    ?.refresh(true, true)
            }
        }.queue()
    }

    private fun findLatestArtifact(base: Path, maxDepth: Int = 8): Path? {
        return try {
            Files.walk(base, maxDepth)
                .use { stream ->
                    stream
                        .filter { path -> path.isRegularFile() }
                        .filter { path ->
                            val ext = path.extension.lowercase()
                            ext == "apk" || ext == "aab"
                        }
                        .max { a, b ->
                            val t1 = Files.getLastModifiedTime(a).toMillis()
                            val t2 = Files.getLastModifiedTime(b).toMillis()
                            t1.compareTo(t2)
                        }
                        .orElse(null)
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("check16k.notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
