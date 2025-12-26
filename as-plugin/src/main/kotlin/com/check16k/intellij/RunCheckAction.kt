package com.check16k.intellij

import com.check16k.core.ArchiveScanner
import com.check16k.core.CheckConfig
import com.check16k.core.Origin
import com.check16k.core.OriginMatch
import com.check16k.core.ReportWriter
import com.check16k.core.ScanReport
import com.check16k.intellij.variant.VariantReader
import com.check16k.intellij.variant.VariantSelection
import com.check16k.intellij.variant.VariantStateService
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class RunCheckAction : AnAction("16kb-check: Scan APK/AAB + SO Origins") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settingsComponent = Check16kSettingsState.getInstance(project)
        val settings = settingsComponent.state

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
                    val autoArtifact = findLatestArtifact(rootDir.toPath())
                    val savedArtifactPath = settings.artifactPath.trim()
                    if (savedArtifactPath.isNotBlank()) {
                        log("Saved artifactPath: $savedArtifactPath")
                    }
                    if (autoArtifact != null) {
                        log("Auto-detected artifact: ${autoArtifact.toAbsolutePath()}")
                    } else {
                        log("Auto-detected artifact: <none>")
                    }

                    fun validateArtifact(path: Path): String? {
                        val p = path.toAbsolutePath().normalize()
                        if (!Files.exists(p)) return "路径不存在：$p"
                        if (!p.isRegularFile()) return "不是文件：$p"
                        val ext = p.extension.lowercase()
                        if (ext != "apk" && ext != "aab") return "不支持的文件类型：$p（仅支持 .apk/.aab）"
                        return null
                    }

                    // 2.1) 弹窗选择 module/variant/abi（需在 EDT）
                    var chosenModule = settings.modulePath
                    var chosenVariant = settings.variantName
                    var chosenAbi = settings.abiFilter
                    var chosenArtifactPath = ""
                    var canceled = false

                    ApplicationManager.getApplication().invokeAndWait {
                        val svc = project.getService(VariantStateService::class.java)
                        svc.installListenersOnce()
                        svc.refreshNow()

                        val detected = svc.currentModulePath?.let { mp ->
                            val v = svc.currentVariant
                            if (!v.isNullOrBlank()) VariantSelection(normalizeGradleModulePath(project, mp), v) else null
                        }

                        // ✅ 模块列表从 settings.gradle 解析，永远是 GradlePath（:app / :xxx:yyy）
                        val androidModules = runReadAction { loadAndroidModules(project, rootDir) }

                        val defaultModule = normalizeGradleModulePath(
                            project,
                            settings.modulePath.ifBlank {
                                detected?.modulePath
                                    ?: svc.guessAppModulePath()
                                    ?: androidModules.firstOrNull()?.modulePath
                                    ?: ":app"
                            }
                        )

                        val moduleShortGuess = shortModuleName(defaultModule)
                        val artifactForGuess = runCatching {
                            if (savedArtifactPath.isNotBlank()) Path.of(savedArtifactPath) else null
                        }.getOrNull()
                        val artifactForGuessOk = artifactForGuess?.let { validateArtifact(it) == null } == true
                        val artifactNameForGuess = (if (artifactForGuessOk) artifactForGuess else autoArtifact)
                            ?.fileName
                            ?.toString()

                        val variantGuessFromArtifact = artifactNameForGuess?.let { guessVariantFromArtifact(it, moduleShortGuess) }
                        svc.setFallbackVariantProvider { variantGuessFromArtifact }

                        val defaultVariant = settings.variantName.ifBlank {
                            val ideModule = findIdeModuleByGradlePath(project, defaultModule)
                            val modelVariant = ideModule?.let { VariantReader.getSelectedVariantFromModels(it) }
                            val facetVariant = ideModule?.let { VariantReader.getSelectedVariantForModule(it) }
                            modelVariant
                                ?: facetVariant
                                ?: detected?.variant
                                ?: svc.currentVariant
                                ?: androidModules.firstOrNull { !it.selectedVariant.isNullOrBlank() }?.selectedVariant
                                ?: variantGuessFromArtifact
                                ?: androidModules.firstOrNull()?.variants?.firstOrNull()
                                ?: "debug"
                        }

                        val defaultAbi = settings.abiFilter
                        val defaultArtifactPath = savedArtifactPath.ifBlank { autoArtifact?.toString().orEmpty() }

                        val dialog = ModuleVariantDialog(
                            project = project,
                            modules = androidModules,
                            defaultArtifactPath = defaultArtifactPath,
                            defaultModule = defaultModule,
                            defaultVariant = defaultVariant,
                            defaultAbi = defaultAbi,
                            logger = { msg -> log(msg) }
                        )

                        if (!dialog.showAndGet()) {
                            canceled = true
                            return@invokeAndWait
                        }

                        chosenModule = normalizeGradleModulePath(project, dialog.selectedModulePath.ifBlank { defaultModule })
                        chosenVariant = dialog.selectedVariant.ifBlank { defaultVariant }
                        chosenAbi = dialog.selectedAbi.trim()
                        chosenArtifactPath = dialog.selectedArtifactPath.trim()

                        settings.artifactPath = chosenArtifactPath
                        settings.modulePath = chosenModule
                        settings.variantName = chosenVariant
                        settings.abiFilter = chosenAbi
                    }
                    if (canceled) return

                    val manualArtifact = if (chosenArtifactPath.isNotBlank()) {
                        runCatching { Path.of(chosenArtifactPath) }.getOrElse { t ->
                            err("Invalid artifact path string: '$chosenArtifactPath' (${t.javaClass.simpleName}: ${t.message})")
                            notify(project, "16kb-check", "产物路径无效：$chosenArtifactPath", NotificationType.ERROR)
                            return
                        }
                    } else {
                        null
                    }

                    val artifact = manualArtifact ?: autoArtifact

                    if (artifact == null) {
                        err("No artifact selected. manualPath='${chosenArtifactPath.ifBlank { "<empty>" }}', autoDetected=${autoArtifact?.toString() ?: "<none>"}")
                        notify(project, "16kb-check", "未找到可用的 APK/AAB：请先构建产物或在弹窗里手动填写产物路径。", NotificationType.WARNING)
                        return
                    }

                    val artifactError = validateArtifact(artifact)
                    if (artifactError != null) {
                        err("Invalid artifact: $artifactError")
                        notify(project, "16kb-check", "产物路径无效：$artifactError", NotificationType.ERROR)
                        return
                    }

                    val artifactSource = if (chosenArtifactPath.isNotBlank()) "manual" else "auto"
                    val variantFromArtifact = artifact.fileName.toString().substringBeforeLast(".")
                    log("Using artifact ($artifactSource): ${artifact.toAbsolutePath()} (variant=$variantFromArtifact)")

                    // 3) 扫描产物
                    indicator.text = "扫描产物..."
                    val scanner = ArchiveScanner(CheckConfig())
                    val baseReport = try {
                        val abiSet = chosenAbi.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        scanner.scan(artifact, variantFromArtifact, emptyMap(), abiSet)
                    } catch (t: Throwable) {
                        err("扫描 APK/AAB 失败：${t.message}")
                        notify(project, "16kb-check", "扫描 APK/AAB 失败：${t.message}", NotificationType.ERROR)
                        return
                    }
                    log("产物扫描完成：total=${baseReport.summary.totalSo}, fail=${baseReport.summary.fail}, warn=${baseReport.summary.warn}")

                    // 4) hash-origins 输出目录
                    val reportDir = File(rootDir, settings.reportDir.ifBlank { "check-result" }).apply { mkdirs() }
                    val hashOriginsJson = File(reportDir, "hash-origins.json")
                    if (hashOriginsJson.exists()) hashOriginsJson.delete()
                    log("Report dir: ${reportDir.absolutePath}")
                    log("hash-origins output: ${hashOriginsJson.absolutePath}")

                    // 5) 执行 Gradle：生成 hash-origins
                    val gradlew = findGradlew(rootDir) ?: run {
                        notify(project, "16kb-check", "未找到 gradlew：请确认项目根目录存在 ./gradlew", NotificationType.ERROR)
                        return
                    }
                    log("Gradlew: ${gradlew.absolutePath}")

                    val modulePath = normalizeGradleModulePath(project, chosenModule.ifBlank { ":app" })
                    val mergeVariantName = chosenVariant.ifBlank { variantFromArtifact }
                    val abiFilter = chosenAbi.trim()
                    log("Selected module/variant: module=$modulePath, variant=$mergeVariantName, abi=${if (abiFilter.isBlank()) "all" else abiFilter}, artifactSource=$artifactSource")

                    val mergeTask = "${modulePath}:merge${mergeVariantName.replaceFirstChar { it.uppercase() }}NativeLibs"

                    val baseCache = File(PathManager.getSystemPath(), "16kb-check")
                    val projectKey = FileUtil.sanitizeFileName(project.name)
                    val workDir = File(baseCache, projectKey).apply { mkdirs() }
                    val initScript = File(workDir, "init-script-16kb-check.gradle")

                    val scriptText = InitScriptProvider.buildInitScript(
                        outputJsonAbsPath = hashOriginsJson.absolutePath,
                        modulePath = modulePath,
                        variantName = mergeVariantName,
                        abiFilter = abiFilter
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

                    // 6) 读取 hash-origins
                    val hashOriginsMap = if (hashOriginsOk) parseHashOrigins(hashOriginsJson) else emptyMap()
                    if (hashOriginsMap.isNotEmpty()) log("hash-origins 解析完成：${hashOriginsMap.size} 个 sha 匹配到来源")
                    else log("hash-origins 为空或解析失败，将输出基础报告")

                    // 7) 输出报告
                    val finalReport: ScanReport = if (hashOriginsMap.isNotEmpty()) {
                        enrichReportWithOrigins(baseReport, hashOriginsMap)
                    } else baseReport

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

    // ------------------------
    // Report/origin helpers（保留你原逻辑）
    // ------------------------

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

        if (!path.isNullOrBlank()) return Origin.File(path)
        if (!projectPath.isNullOrBlank()) return Origin.Project(projectPath, source = "merged_native_libs")
        return Origin.Unknown
    }

    // ------------------------
    // Artifact / Gradle helpers
    // ------------------------

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

    // ------------------------
    // Module / variant discovery（✅核心修复点）
    // ------------------------

    private data class AndroidModuleEntry(
        val modulePath: String,     // 永远是 GradlePath: :app / :feature:xxx
        val variants: List<String>,
        val selectedVariant: String?
    )

    /**
     * ✅ 不再用 Module.name 当 modulePath
     * ✅ 从 settings.gradle(.kts) 解析 include 的 GradlePath
     */
    private fun loadAndroidModules(project: Project, rootDir: File?): List<AndroidModuleEntry> {
        val gradlePaths = rootDir?.let { loadGradleModulePathsFromSettings(it) }.orEmpty()
        val paths = if (gradlePaths.isNotEmpty()) gradlePaths else listOf(":app")

        return paths.map { gradlePath ->
            val modulePath = normalizeGradleModulePath(project, gradlePath)

            val ideModule = findIdeModuleByGradlePath(project, modulePath)
            val selectedVariant = ideModule?.let { VariantReader.getSelectedVariantFromModels(it) }
                ?: ideModule?.let { VariantReader.getSelectedVariantForModule(it) }

            val diskVariants = discoverVariantsFromDisk(rootDir, modulePath)
            val variants = diskVariants.distinct().ifEmpty { listOf("debug", "release") }

            AndroidModuleEntry(
                modulePath = modulePath,
                variants = variants,
                selectedVariant = selectedVariant
            )
        }.sortedBy { it.modulePath }
    }

    private fun loadGradleModulePathsFromSettings(rootDir: File): List<String> {
        val f = listOf(
            File(rootDir, "settings.gradle.kts"),
            File(rootDir, "settings.gradle")
        ).firstOrNull { it.exists() && it.isFile } ?: return emptyList()

        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()

        // 抓取 ':a:b' 形式
        val r = Regex("""['"](:[A-Za-z0-9_\-:]+)['"]""")
        return r.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    private fun discoverVariantsFromDisk(rootDir: File?, modulePath: String): List<String> {
        if (rootDir == null) return emptyList()
        val rel = modulePath.removePrefix(":").split(':').filter { it.isNotBlank() }.joinToString(File.separator)
        val moduleDir = File(rootDir, rel)
        val merged = File(moduleDir, "build/intermediates/merged_native_libs")
        if (!merged.exists() || !merged.isDirectory) return emptyList()
        return merged.listFiles()?.filter { it.isDirectory }?.map { it.name }?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * ✅ 关键：净化输入，只允许输出 GradlePath
     * - "bayarlah_vietnam.app" -> ":app"
     * - ":bayarlah_vietnam.app" -> ":app"
     * - "app" -> ":app"
     * - ":feature:app" 保留
     * - ":bayarlah_vietnam"（工程名误入）-> ":app"
     */
    private fun normalizeGradleModulePath(project: Project, raw: String?): String {
        if (raw.isNullOrBlank()) return ":app"

        var s = raw.trim()
        s = s.removePrefix(":")

        // IDE module name: bayarlah_vietnam.app
        if (s.contains('.') && !s.contains(':')) {
            s = s.substringAfterLast('.')
        }

        // 工程名误入
        if (s == project.name) {
            s = "app"
        }

        return if (s.startsWith(":")) s else ":$s"
    }

    /**
     * ✅ 用 Facet 反射取 GradlePath 精确匹配 IDE Module
     * 找不到再做兜底：endsWith(".app") / name=="app" / 尾段匹配
     */
    private fun findIdeModuleByGradlePath(project: Project, gradlePathRaw: String): Module? {
        val target = normalizeGradleModulePath(project, gradlePathRaw)

        val mm = ModuleManager.getInstance(project)
        val modules = mm.modules

        // 1) 精确：Facet 里能拿到 modulePath/gradlePath 的，直接比对（最稳）
        modules.firstOrNull { m ->
            val facetModulePath = VariantReader.getFacetModulePath(m)
            facetModulePath != null && facetModulePath == target
        }?.let { return it }

        // 2) 兜底：常见命名 app / xxx.app
        val tail = target.substringAfterLast(":")
        modules.firstOrNull { it.name == tail }?.let { return it }
        modules.firstOrNull { it.name.endsWith(".$tail") }?.let { return it }

        // 3) 最后：弱匹配
        return modules.firstOrNull { (":" + it.name).endsWith(target) }
    }

    private fun guessVariantFromArtifact(fileName: String, moduleShort: String?): String? {
        val base = fileName.substringBeforeLast(".")
        var name = base
        if (!moduleShort.isNullOrBlank() && base.startsWith(moduleShort)) {
            name = base.removePrefix(moduleShort).trimStart('-', '_')
        }
        val tokens = name.split('-', '_').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val first = tokens.first().replaceFirstChar { it.lowercaseChar() }
        val rest = tokens.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        return (first + rest)
    }

    // ------------------------
    // Dialog
    // ------------------------

    private class ModuleVariantDialog(
        private val project: Project,
        private val modules: List<AndroidModuleEntry>,
        private val defaultArtifactPath: String,
        private val defaultModule: String,
        private val defaultVariant: String,
        private val defaultAbi: String,
        private val logger: (String) -> Unit
    ) : DialogWrapper(project, true) {

        private val artifactField = JTextField()
        private val browseButton = JButton("Browse...")
        private val moduleCombo = JComboBox<ModuleComboItem>()
        private val variantCombo = JComboBox<String>()
        private val abiCombo = JComboBox<String>()
        private val infoLabel = JLabel("可编辑：artifact 为空则自动查找；module/variant/abi 可直接输入或从下拉选择")

        val selectedArtifactPath: String
            get() = artifactField.text.trim()
        val selectedModulePath: String
            get() = (moduleCombo.selectedItem as? ModuleComboItem)?.value?.trim().orEmpty()
        val selectedVariant: String
            get() = variantCombo.selectedItem?.toString()?.trim().orEmpty()
        val selectedAbi: String
            get() = abiCombo.selectedItem?.toString()?.trim().orEmpty()

        init {
            title = "选择 Artifact / Module / Variant / ABI"
            artifactField.text = defaultArtifactPath
            moduleCombo.isEditable = true
            variantCombo.isEditable = true
            abiCombo.isEditable = true

            browseButton.addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                    title = "Select APK/AAB"
                    description = "Select an .apk or .aab file"
                }
                val initial = artifactField.text.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
                val chosen = FileChooser.chooseFile(descriptor, project, initial)
                if (chosen != null) {
                    artifactField.text = chosen.path
                    logger("Artifact selected from file chooser: ${chosen.path}")
                }
            }

            modules.forEach { moduleCombo.addItem(ModuleComboItem(it.modulePath)) }
            if (moduleCombo.itemCount == 0) {
                moduleCombo.addItem(ModuleComboItem(defaultModule))
            }
            moduleCombo.renderer = SimpleListCellRenderer.create<ModuleComboItem> { label, value, _ ->
                label.text = value?.display ?: value?.value ?: ""
            }
            moduleCombo.selectedItem = ModuleComboItem(defaultModule)

            fun refreshVariants(modulePathRaw: String?) {
                logger("Refreshing variants for moduleRaw=$modulePathRaw")
                variantCombo.removeAllItems()

                val modulePath = if (modulePathRaw.isNullOrBlank()) defaultModule else modulePathRaw
                val entry = modules.firstOrNull { it.modulePath == modulePath }
                logger("Module entry variants=${entry?.variants?.joinToString() ?: "[]"}, selected=${entry?.selectedVariant}")

                val candidates = LinkedHashSet<String>()
                candidates.addAll(entry?.variants.orEmpty())

                val ideModule = runCatching { findIdeModuleByGradlePath(project, modulePath) }.getOrNull()
                val modelVariant = ideModule?.let { VariantReader.getSelectedVariantFromModels(it) }
                val facetVariant = ideModule?.let { VariantReader.getSelectedVariantForModule(it) }
                logger("Variant lookup => targetGradlePath=$modulePath, ideModule=${ideModule?.name}, modelVariant=$modelVariant, facetVariant=$facetVariant")

                val selected = modelVariant?.takeIf { it.isNotBlank() }
                    ?: facetVariant?.takeIf { it.isNotBlank() }
                    ?: entry?.selectedVariant?.takeIf { !it.isNullOrBlank() }
                    ?: defaultVariant

                if (candidates.isEmpty()) {
                    candidates.add(defaultVariant)
                    candidates.add("debug")
                    candidates.add("release")
                }

                if (!selected.isNullOrBlank()) candidates.add(selected)

                candidates.forEach { variantCombo.addItem(it) }
                variantCombo.selectedItem = selected
                logger("Variants after refresh: ${candidates.joinToString()} | selected=$selected")
            }

            refreshVariants(defaultModule)
            moduleCombo.addActionListener {
                val mp = (moduleCombo.selectedItem as? ModuleComboItem)?.value
                refreshVariants(mp)
            }

            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abiCombo.addItem(it) }
            if (defaultAbi.isNotBlank() && (0 until abiCombo.itemCount).none { defaultAbi == abiCombo.getItemAt(it) }) {
                abiCombo.addItem(defaultAbi)
            }
            // 默认选择 arm64-v8a
            abiCombo.selectedItem = if (defaultAbi.isNotBlank()) defaultAbi else "arm64-v8a"

            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(java.awt.GridBagLayout())
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                gridx = 0
                gridy = 0
                weightx = 0.0
                insets = java.awt.Insets(6, 6, 6, 6)
            }

            panel.add(JLabel("Artifact (.apk/.aab):"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(JPanel(BorderLayout(6, 0)).apply {
                add(artifactField, BorderLayout.CENTER)
                add(browseButton, BorderLayout.EAST)
            }, gbc)

            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            panel.add(JLabel("Module:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(moduleCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 2
            gbc.weightx = 0.0
            panel.add(JLabel("Variant:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(variantCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 3
            gbc.weightx = 0.0
            panel.add(JLabel("ABI:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(abiCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 4
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            infoLabel.foreground = java.awt.Color(0x8f, 0x9b, 0xa7)
            panel.add(infoLabel, gbc)

            return panel
        }

        private fun findIdeModuleByGradlePath(project: Project, gradlePath: String): Module? {
            // 复用外部同名逻辑（这里写一份简单调用，避免 inner class 访问权限问题）
            val target = if (gradlePath.startsWith(":")) gradlePath else ":$gradlePath"
            val modules = ModuleManager.getInstance(project).modules

            modules.firstOrNull { m ->
                val p = VariantReader.getFacetModulePath(m)
                p != null && p == target
            }?.let { return it }

            val tail = target.substringAfterLast(":")
            modules.firstOrNull { it.name == tail }?.let { return it }
            modules.firstOrNull { it.name.endsWith(".$tail") }?.let { return it }
            return modules.firstOrNull { (":" + it.name).endsWith(target) }
        }
    }

    private data class ModuleComboItem(val value: String) {
        val display: String = shortModuleName(value)
        override fun toString(): String = display
    }

    private inline fun <T> runReadAction(crossinline block: () -> T): T =
        ApplicationManager.getApplication().runReadAction<T> { block() }

    private fun contentOrNull(primitive: kotlinx.serialization.json.JsonPrimitive?): String? =
        primitive?.let { runCatching { it.content }.getOrNull() }
}

private fun shortModuleName(path: String?): String {
    if (path.isNullOrBlank()) return ""
    val parts = path.split(':', '.').filter { it.isNotBlank() }
    return parts.lastOrNull() ?: path
}
