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
import com.intellij.openapi.ui.DialogWrapper
import com.check16k.intellij.variant.VariantStateService
import com.check16k.intellij.variant.VariantSelection
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.ui.SimpleListCellRenderer
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
                    val artifact = findLatestArtifact(rootDir.toPath())
                    if (artifact == null) {
                        notify(project, "16kb-check", "未找到可用的 APK/AAB，请先构建产物。", NotificationType.WARNING)
                        return
                    }
                    val variantFromArtifact = artifact.fileName.toString().substringBeforeLast(".")
                    log("Artifact: ${artifact.toAbsolutePath()} (variant=$variantFromArtifact)")

                    // 2.1) 弹窗选择 module/variant/abi（需在 EDT）
                    var chosenModule = settings.modulePath
                    var chosenVariant = settings.variantName
                    var chosenAbi = settings.abiFilter
                    var canceled = false
                    ApplicationManager.getApplication().invokeAndWait {
                        val svc = project.getService(VariantStateService::class.java)
                        val detected = svc.currentModulePath?.let { mp ->
                            val v = svc.currentVariant
                            if (!v.isNullOrBlank()) VariantSelection(normalizeModulePath(mp), v) else null
                        }

                        val androidModules = runReadAction { loadAndroidModules(project, rootDir) }
                        val moduleShortGuess = shortModuleName(androidModules.firstOrNull()?.modulePath ?: settings.modulePath)
                        val variantGuessFromArtifact = guessVariantFromArtifact(artifact.fileName.toString(), moduleShortGuess)
                        val defaultModule = settings.modulePath.ifBlank {
                            detected?.modulePath
                                ?: svc.guessAppModulePath()
                                ?: androidModules.firstOrNull()?.modulePath
                                ?: ":app"
                        }
                        val defaultVariant = settings.variantName.ifBlank {
                            detected?.variant
                                ?: svc.currentVariant
                                ?: androidModules.firstOrNull { !it.selectedVariant.isNullOrBlank() }?.selectedVariant
                                ?: variantGuessFromArtifact
                                ?: androidModules.firstOrNull()?.variants?.firstOrNull()
                                ?: "debug"
                        }
                        val defaultAbi = settings.abiFilter

                        val dialog = ModuleVariantDialog(project, androidModules, defaultModule, defaultVariant, defaultAbi)
                        if (!dialog.showAndGet()) {
                            canceled = true
                            return@invokeAndWait
                        }

                        chosenModule = normalizeModulePath(dialog.selectedModulePath.ifBlank { defaultModule })
                        chosenVariant = dialog.selectedVariant.ifBlank { defaultVariant }
                        chosenAbi = dialog.selectedAbi.trim()
                        settings.modulePath = chosenModule
                        settings.variantName = chosenVariant
                        settings.abiFilter = chosenAbi
                    }
                    if (canceled) return

                    // 3) 扫描产物（先得到 fail/warn 统计）
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

                    // 选取 module / variant / abi：弹窗结果 + 兜底提示
                    val modulePath = normalizeModulePath(chosenModule.ifBlank { ":app" })
                    val mergeVariantName = chosenVariant.ifBlank { variantFromArtifact }
                    val abiFilter = chosenAbi.trim()
                    log("Selected module/variant: module=$modulePath, variant=$mergeVariantName, abi=${if (abiFilter.isBlank()) "all" else abiFilter}")

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

    private data class AndroidModuleEntry(
        val modulePath: String,
        val variants: List<String>,
        val selectedVariant: String?
    )

    private fun loadAndroidModules(project: Project, rootDir: File?): List<AndroidModuleEntry> {
        val moduleManager = ModuleManager.getInstance(project)
        val getInfo = getAndroidModelGetMethod()
        if (getInfo == null) {
            // Android plugin不可用，返回普通模块列表以便用户手填
            return moduleManager.modules
                .filterNot { isTestModule(it.name) }
                .map {
                    val path = normalizeModulePath(":" + it.name)
                    AndroidModuleEntry(path, discoverVariantsFromDisk(rootDir, path), null)
                }
        }

        val (getMethod, modelClass) = getInfo

        val variantNameReader: (Any) -> String? = { variant ->
            runCatching {
                variant.javaClass.methods.firstOrNull { it.name == "getName" }?.invoke(variant) as? String
            }.getOrNull()
        }

        val variantListReader: (Any) -> List<String> = { model ->
            val variants = mutableListOf<String>()
            listOf(
                "getVariantNames", "getAllVariantNames",
                "getVariants", "getAllVariants"
            ).forEach { mName ->
                val m = model.javaClass.methods.firstOrNull { it.name == mName }
                if (m != null) {
                    val result = runCatching { m.invoke(model) }.getOrNull()
                    when (result) {
                        is Iterable<*> -> result.forEach { v ->
                            if (v is String) variants.add(v)
                            else if (v != null) variantNameReader(v)?.let { variants.add(it) }
                        }
                        is Array<*> -> result.forEach { v ->
                            if (v is String) variants.add(v)
                            else if (v != null) variantNameReader(v)?.let { variants.add(it) }
                        }
                    }
                }
            }
            variants.distinct()
        }

        return moduleManager.modules.mapNotNull { module ->
            if (isTestModule(module.name)) return@mapNotNull null
            val model = runCatching { getMethod.invoke(null, module) }.getOrNull() ?: return@mapNotNull null
            val modulePathRaw = runCatching {
                model.javaClass.methods.firstOrNull { it.name == "getModulePath" }?.invoke(model) as? String
            }.getOrNull()
            val modulePath = normalizeModulePath(modulePathRaw ?: ":" + module.name)
            val selectedVariant = runCatching {
                readSelectedVariant(model, modelClass)
            }.getOrNull()
            val variants = (variantListReader(model) + discoverVariantsFromDisk(rootDir, modulePath))
                .distinct()
                .ifEmpty { listOf("debug", "release") }
            AndroidModuleEntry(modulePath, variants, selectedVariant)
        }.sortedBy { it.modulePath }
    }

    private fun getAndroidModelGetMethod(): Pair<java.lang.reflect.Method, Class<*>>? {
        val candidates = listOf(
            "com.android.tools.idea.gradle.project.model.AndroidModuleModel",
            "com.android.tools.idea.gradle.project.model.GradleAndroidModel"
        )
        candidates.forEach { name ->
            val clazz = runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull()
            if (clazz != null) {
                val getMethod = clazz.methods.firstOrNull {
                    it.name == "get" && it.parameterCount == 1 && Module::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (getMethod != null) return getMethod to clazz
            }
        }
        return null
    }

    private fun readSelectedVariant(model: Any, modelClass: Class<*>): String? {
        runCatching { modelClass.methods.firstOrNull { it.name == "getSelectedVariantName" }?.invoke(model) as? String }
            .getOrNull()?.let { if (it.isNotBlank()) return it }
        runCatching {
            val v = modelClass.methods.firstOrNull { it.name == "getSelectedVariant" }?.invoke(model)
            if (v != null) v.javaClass.methods.firstOrNull { it.name == "getName" }?.invoke(v) as? String else null
        }.getOrNull()?.let { if (!it.isNullOrBlank()) return it }
        return null
    }

    private fun discoverVariantsFromDisk(rootDir: File?, modulePath: String): List<String> {
        if (rootDir == null) return emptyList()
        val rel = modulePath.removePrefix(":").split(':').filter { it.isNotBlank() }.joinToString(File.separator)
        val moduleDir = File(rootDir, rel)
        val merged = File(moduleDir, "build/intermediates/merged_native_libs")
        if (!merged.exists() || !merged.isDirectory) return emptyList()
        return merged.listFiles()?.filter { it.isDirectory }?.map { it.name }?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun isTestModule(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return lower.contains("test") && !lower.contains("contest")
    }

    private fun normalizeModulePath(path: String?): String {
        if (path.isNullOrBlank()) return ":app"
        // 处理类似 "root.app" 或 "root:app" 形式，保留末尾段作为 module
        val trimmed = path.trim()
        val parts = trimmed.split(':', '.').filter { it.isNotBlank() }
        val tail = parts.lastOrNull() ?: trimmed
        return if (tail.startsWith(":")) tail else ":" + tail
    }

    private fun guessVariantFromArtifact(fileName: String, moduleShort: String?): String? {
        val base = fileName.substringBeforeLast(".")
        // 如 app-mobile-prodApp-debug.apk -> mobileProdAppDebug
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

    private class ModuleVariantDialog(
        project: Project,
        modules: List<AndroidModuleEntry>,
        defaultModule: String,
        defaultVariant: String,
        defaultAbi: String
    ) : DialogWrapper(project, true) {

        private val moduleCombo = JComboBox<ModuleComboItem>()
        private val variantCombo = JComboBox<String>()
        private val abiCombo = JComboBox<String>()
        private val infoLabel = JLabel("可编辑：直接输入 module/variant/abi，或从下拉选择")

        val selectedModulePath: String
            get() = (moduleCombo.selectedItem as? ModuleComboItem)?.value?.trim().orEmpty()
        val selectedVariant: String
            get() = variantCombo.selectedItem?.toString()?.trim().orEmpty()
        val selectedAbi: String
            get() = abiCombo.selectedItem?.toString()?.trim().orEmpty()

        init {
            title = "选择 Module / Variant / ABI"
            moduleCombo.isEditable = true
            variantCombo.isEditable = true
            abiCombo.isEditable = true

            modules.forEach { moduleCombo.addItem(ModuleComboItem(it.modulePath)) }
            if (moduleCombo.itemCount == 0) {
                moduleCombo.addItem(ModuleComboItem(defaultModule))
            }
            moduleCombo.renderer = SimpleListCellRenderer.create<ModuleComboItem> { label, value, _ ->
                label.text = value?.display ?: value?.value ?: ""
            }
            moduleCombo.selectedItem = ModuleComboItem(defaultModule)

            fun refreshVariants(modulePath: String?) {
                variantCombo.removeAllItems()
                val entry = modules.firstOrNull { it.modulePath == modulePath }
                val variants = entry?.variants?.takeIf { it.isNotEmpty() } ?: emptyList()
                variants.forEach { variantCombo.addItem(it) }
                if (variantCombo.itemCount == 0) {
                    variantCombo.addItem(defaultVariant)
                }
                variantCombo.selectedItem = entry?.selectedVariant ?: defaultVariant
            }

            refreshVariants(defaultModule)
            moduleCombo.addActionListener { refreshVariants((moduleCombo.selectedItem as? ModuleComboItem)?.value) }

            listOf("", "arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abiCombo.addItem(it) }
            if (defaultAbi.isNotBlank() && (0 until abiCombo.itemCount).none { defaultAbi == abiCombo.getItemAt(it) }) {
                abiCombo.addItem(defaultAbi)
            }
            abiCombo.selectedItem = defaultAbi

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

            panel.add(JLabel("Module:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(moduleCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            panel.add(JLabel("Variant:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(variantCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 2
            gbc.weightx = 0.0
            panel.add(JLabel("ABI:"), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(abiCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 3
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            infoLabel.foreground = java.awt.Color(0x8f, 0x9b, 0xa7)
            panel.add(infoLabel, gbc)

            return panel
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
