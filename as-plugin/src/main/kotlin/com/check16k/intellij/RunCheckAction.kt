package com.check16k.intellij

import com.check16k.core.ArchiveScanner
import com.check16k.core.CheckConfig
import com.check16k.core.Origin
import com.check16k.core.OriginMatch
import com.check16k.core.ReportWriter
import com.check16k.core.ScanReport
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class RunCheckAction : AnAction("16kb-check: Scan APK/AAB + SO Origins") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = Check16kSettingsState.getInstance(project).state

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "16kb-check - Scan APK/AAB + SO Origins", true) {

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("16kb-check")
                    toolWindow?.activate(null)

                    val console = CheckConsoleHolder.console
                    if (console == null) {
                        notify(project, "16kb-check", "Console 未初始化：请确认 ToolWindow 已注册并打开过一次", NotificationType.ERROR)
                        return
                    }

                    fun log(msg: String) {
                        ApplicationManager.getApplication().invokeLater {
                            console.print("[16KB] $msg\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                    }

                    fun err(msg: String) {
                        ApplicationManager.getApplication().invokeLater {
                            console.print("[16KB][ERROR] $msg\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }
                    }

                    ApplicationManager.getApplication().invokeLater { console.clear() }
                    log("Start. (Cancelable Background Task)")

                    // 1) 项目根目录
                    val rootDir = project.guessProjectDir()?.toNioPath()?.toFile()
                        ?: run {
                            notify(project, "16kb-check", "无法定位项目根目录（guessProjectDir=null）", NotificationType.ERROR)
                            return
                        }
                    log("ProjectRoot: ${rootDir.absolutePath}")

                    // 2) 定位产物（APK/AAB）
                    indicator.text = "查找 APK/AAB 产物..."
                    val artifact = findLatestArtifact(rootDir.toPath())
                    if (artifact == null) {
                        notify(project, "16kb-check", "未找到可用的 APK/AAB，请先构建产物。", NotificationType.WARNING)
                        return
                    }
                    val variantFromArtifact = artifact.fileName.toString().substringBeforeLast(".")
                    log("Artifact: ${artifact.toAbsolutePath()} (variant=$variantFromArtifact)")

                    // 3) 扫描产物（先得到 fail/warn 统计）
                    indicator.text = "扫描产物..."
                    val scanner = ArchiveScanner(CheckConfig())
                    val baseReport = try {
                        scanner.scan(artifact, variantFromArtifact, emptyMap())
                    } catch (t: Throwable) {
                        err("扫描 APK/AAB 失败：${t.message}")
                        notify(project, "16kb-check", "扫描 APK/AAB 失败：${t.message}", NotificationType.ERROR)
                        return
                    }
                    log("产物扫描完成：total=${baseReport.summary.totalSo}, fail=${baseReport.summary.fail}, warn=${baseReport.summary.warn}")

                    // 4) 准备 hash-origins 输出目录
                    val reportDir = File(rootDir, settings.reportDir.ifBlank { "check-result" }).apply { mkdirs() }
                    val hashOriginsJson = File(reportDir, "hash-origins.json")
                    if (hashOriginsJson.exists()) hashOriginsJson.delete()
                    log("Report dir: ${reportDir.absolutePath}")
                    log("hash-origins output: ${hashOriginsJson.absolutePath}")

                    // 5) 执行 Gradle：生成 hash-origins
                    val gradlew = findGradlew(rootDir)
                        ?: run {
                        notify(project, "16kb-check", "未找到 gradlew：请确认项目根目录存在 ./gradlew", NotificationType.ERROR)
                            return
                        }
                    log("Gradlew: ${gradlew.absolutePath}")

                    // ======= 目前仍写死 module / variant（后续可 UI 配置化） =======
                    val modulePath = ":app"
                    val mergeVariantName = "mobileProdAppDebug"
                    val mergeTask = "${modulePath}:merge${mergeVariantName.replaceFirstChar { it.uppercase() }}NativeLibs"
                    // ======================================================================

                    val baseCache = File(PathManager.getSystemPath(), "16kb-check")
                    val projectKey = FileUtil.sanitizeFileName(project.name)
                    val workDir = File(baseCache, projectKey).apply { mkdirs() }
                    val initScript = File(workDir, "init-script-16kb-check.gradle")

                    val scriptText = InitScriptProvider.buildInitScript(
                        outputJsonAbsPath = hashOriginsJson.absolutePath,
                        modulePath = modulePath,
                        variantName = mergeVariantName
                    )

                    indicator.text = "Writing init-script..."
                    log("Temp init-script dir: ${workDir.absolutePath}")
                    initScript.writeText(scriptText, Charsets.UTF_8)

                    val cmd = listOf(
                        gradlew.absolutePath,
                        "--no-daemon",
                        "-I", initScript.absolutePath,
                        mergeTask
                    )

                    log("Executing Gradle (hash-origins)...")
                    log("CMD: ${cmd.joinToString(" ")}")
                    indicator.text = "Running Gradle with init-script..."

                    var process: Process? = null
                    var hashOriginsOk = false

                    try {
                        val pb = ProcessBuilder(cmd)
                            .directory(rootDir)
                            .redirectErrorStream(false)

                        process = pb.start()
                        val pRef = process!!

                        val tOut = Thread {
                            try {
                                BufferedReader(InputStreamReader(pRef.inputStream, Charsets.UTF_8)).useLines { lines ->
                                    lines.forEach { line ->
                                        if (indicator.isCanceled) return@forEach
                                        ApplicationManager.getApplication().invokeLater {
                                            console.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                                        }
                                    }
                                }
                            } catch (_: Throwable) { }
                        }

                        val tErr = Thread {
                            try {
                                BufferedReader(InputStreamReader(pRef.errorStream, Charsets.UTF_8)).useLines { lines ->
                                    lines.forEach { line ->
                                        if (indicator.isCanceled) return@forEach
                                        ApplicationManager.getApplication().invokeLater {
                                            console.print(line + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                                        }
                                    }
                                }
                            } catch (_: Throwable) { }
                        }

                        tOut.isDaemon = true
                        tErr.isDaemon = true
                        tOut.start()
                        tErr.start()

                        val deadlineNs = System.nanoTime() + TimeUnit.MINUTES.toNanos(15)

                        while (true) {
                            if (indicator.isCanceled) {
                                err("Canceled by user. Killing Gradle process...")
                                pRef.destroy()
                                pRef.destroyForcibly()
                                notify(project, "16kb-check", "已取消：Gradle 进程已终止", NotificationType.WARNING)
                                return
                            }

                            val finished = pRef.waitFor(300, TimeUnit.MILLISECONDS)
                            if (finished) break

                            if (System.nanoTime() > deadlineNs) {
                                err("Gradle timeout (>15min). Killing process...")
                                pRef.destroyForcibly()
                                notify(project, "16kb-check", "Gradle 执行超时（>15 分钟）", NotificationType.ERROR)
                                break
                            }
                        }

                        tOut.join(1500)
                        tErr.join(1500)

                        val exit = if (pRef.isAlive) -1 else pRef.exitValue()
                        if (exit == 0 && hashOriginsJson.exists() && hashOriginsJson.length() > 0L) {
                            indicator.text = "Finalizing..."
                            log("Gradle finished (exit=0). hash-origins generated.")
                            hashOriginsOk = true
                        } else {
                            err("Gradle 未成功生成 hash-origins（exit=$exit, exists=${hashOriginsJson.exists()}, size=${hashOriginsJson.length()}）")
                            err("Tip: merged_native_libs 为空/未生成，通常说明 merge<Variant>NativeLibs 没跑成功或 variant 不存在。")
                            notify(project, "16kb-check", "脚本执行完成，但 hash-origins 未生成或为空。", NotificationType.WARNING)
                        }

                    } catch (t: Throwable) {
                        err("Exception: ${t.message}")
                        notify(project, "16kb-check", "执行异常：${t.message}", NotificationType.ERROR)
                    } finally {
                        try {
                            if (initScript.exists()) {
                                initScript.delete()
                                log("Deleted init-script: ${initScript.absolutePath}")
                            }
                        } catch (_: Throwable) { }

                        try {
                            process?.let { if (it.isAlive) it.destroyForcibly() }
                        } catch (_: Throwable) { }
                    }

                    // 6) 读取 hash-origins，构建 sha -> Origin 映射
                    val hashOriginsMap = if (hashOriginsOk) {
                        parseHashOrigins(hashOriginsJson)
                    } else {
                        emptyMap()
                    }
                    if (hashOriginsMap.isNotEmpty()) {
                        log("hash-origins 解析完成：${hashOriginsMap.size} 个 sha 匹配到来源")
                    } else {
                        log("hash-origins 为空或解析失败，将输出基础报告")
                    }

                    // 7) 合并来源信息，输出报告
                    val finalReport: ScanReport = if (hashOriginsMap.isNotEmpty()) {
                        enrichReportWithOrigins(baseReport, hashOriginsMap)
                    } else {
                        baseReport
                    }

                    val variantNameForReport = finalReport.variant ?: variantFromArtifact
                    val jsonOut = reportDir.toPath().resolve("$variantNameForReport.json")
                    val htmlOut = reportDir.toPath().resolve("$variantNameForReport.html")
                    try {
                        ReportWriter.writeJson(finalReport, jsonOut, pretty = true)
                        if (settings.htmlReport) {
                            ReportWriter.writeHtml(finalReport, htmlOut)
                        }
                    } catch (t: Throwable) {
                        err("写入报告失败：${t.message}")
                        notify(project, "16kb-check", "写入报告失败：${t.message}", NotificationType.ERROR)
                        return
                    }

                    ApplicationManager.getApplication().invokeLater {
                        VirtualFileManager.getInstance().syncRefresh()
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(reportDir)?.refresh(true, true)
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(hashOriginsJson)?.refresh(false, false)
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jsonOut.toFile())?.refresh(false, false)
                        if (settings.htmlReport) {
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(htmlOut.toFile())?.refresh(false, false)
                        }
                    }

                    val severity = if (finalReport.summary.fail > 0) NotificationType.WARNING else NotificationType.INFORMATION
                    val summary = "扫描完成：total=${finalReport.summary.totalSo}, fail=${finalReport.summary.fail}, warn=${finalReport.summary.warn}"
                    val originNote = if (hashOriginsMap.isEmpty()) "\n来源信息未补充（hash-origins 为空/失败）。" else "\n来源已补充到报告。"
                    val htmlNote = if (settings.htmlReport) "\nHTML 报告已保存到同目录。" else ""
                    notify(project, "16kb-check", summary + "\n报告已保存：" + jsonOut + htmlNote + originNote, severity)
                }
            }
        )
    }

    private fun enrichReportWithOrigins(
        report: ScanReport,
        originMap: Map<String, List<Origin>>
    ): ScanReport {
        val items = report.items.map { item ->
            val origins = originMap[item.sha256].orEmpty()
            if (origins.isEmpty()) item else item.copy(origin = origins.map { OriginMatch(it, 1.0) })
        }
        return report.copy(items = items)
    }

    private fun parseHashOrigins(outJson: File): Map<String, List<Origin>> {
        return try {
            val json = Json.parseToJsonElement(outJson.readText(Charsets.UTF_8)).jsonObject
            val items = json["items"]?.jsonArray ?: return emptyMap()
            val map = mutableMapOf<String, MutableList<Origin>>()
            items.forEach { element ->
                val obj = element.jsonObject
                val sha = contentOrNull(obj["sha256"]?.jsonPrimitive) ?: return@forEach
                val depId = contentOrNull(obj["dependency"]?.jsonObject?.get("id")?.jsonPrimitive)
                val projectPath = contentOrNull(obj["projectPath"]?.jsonPrimitive)
                val originPath = contentOrNull(obj["origin"]?.jsonObject?.get("path")?.jsonPrimitive)
                val matchPath = contentOrNull(
                    obj["origin"]?.jsonObject
                        ?.get("match")?.jsonObject
                        ?.get("path")?.jsonPrimitive
                )

                val origin = toOrigin(depId, projectPath, originPath, matchPath)
                if (origin != null) {
                    map.getOrPut(sha) { mutableListOf() }.add(origin)
                }
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun toOrigin(
        depId: String?,
        projectPath: String?,
        originPath: String?,
        matchPath: String?
    ): Origin? {
        val path = originPath ?: matchPath

        if (!depId.isNullOrBlank()) {
            val parts = depId.split(":")
            if (parts.size == 3) {
                // 优先用 path 便于可读；无法取到 path 时退回 Maven 信息
                if (!path.isNullOrBlank()) return Origin.File("${depId} | $path")
                return Origin.Maven(parts[0], parts[1], parts[2])
            }
            if (depId == "local" && !projectPath.isNullOrBlank()) {
                if (!path.isNullOrBlank()) return Origin.File("local | $path")
                return Origin.Project(projectPath, source = "jniLibs")
            }
            if (depId != "unknown") {
                return if (!path.isNullOrBlank()) Origin.File("$depId | $path") else Origin.File(depId)
            }
        }

        if (!path.isNullOrBlank()) {
            return Origin.File(path)
        }
        if (!projectPath.isNullOrBlank()) {
            return Origin.Project(projectPath, source = "merged_native_libs")
        }
        return Origin.Unknown
    }

    private fun findLatestArtifact(base: Path, maxDepth: Int = 8): Path? {
        return try {
            Files.walk(base, maxDepth).use { stream ->
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

    private fun findGradlew(rootDir: File): File? {
        val unix = File(rootDir, "gradlew")
        if (unix.exists() && unix.isFile) {
            unix.setExecutable(true)
            return unix
        }
        val win = File(rootDir, "gradlew.bat")
        if (win.exists() && win.isFile) return win
        return null
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("16kb-check")
        group.createNotification(title, content, type).notify(project)
    }

    private fun contentOrNull(primitive: kotlinx.serialization.json.JsonPrimitive?): String? =
        primitive?.let { runCatching { it.content }.getOrNull() }
}
