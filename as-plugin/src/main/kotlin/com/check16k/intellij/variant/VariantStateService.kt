package com.check16k.intellij.variant

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project 级别缓存当前 app module 的选中 variant，并提供监听。
 *
 * 注意：
 * - modulePath 必须是 GradlePath（:app / :feature:xxx），不要用 IDE module.name 直接拼
 * - variant 优先来自 Model，其次来自 Facet(IdeaAndroidProject.selectedVariantName)
 */
@Service(Service.Level.PROJECT)
class VariantStateService(private val project: Project) {

    @Volatile
    private var current: VariantSelection? = null
    val currentVariant: String? get() = current?.variant
    val currentModulePath: String? get() = current?.modulePath

    private val listeners = CopyOnWriteArrayList<(VariantSelection?) -> Unit>()
    @Volatile private var installed = false

    fun addListener(l: (VariantSelection?) -> Unit) {
        listeners += l
        l(current)
    }

    /**
     * 立即读取当前 app module 的选中 variant，并广播。
     */
    fun refreshNow() {
        val selection = readCurrentAppSelection()
        current = selection
        listeners.forEach { it(selection) }
    }

    /**
     * 安装监听：Gradle Sync + Build Variant 变化。
     */
    fun installListenersOnce() {
        if (installed) return
        installed = true

        // Gradle Sync 监听
        VariantSyncListener.install(project) { refreshNow() }

        // Build Variant 切换监听（若可用）
        VariantSelectionListener.install(project) { refreshNow() }

        // 首次读取
        refreshNow()
    }

    /**
     * 兜底：返回 app 的 GradlePath（优先 facet.getModulePath()==":app"）
     */
    fun guessAppModulePath(): String = AppModuleLocator.findAppGradlePath(project)

    // -------------------- private --------------------

    private fun readCurrentAppSelection(): VariantSelection? {
        val ideAppModule = AppModuleLocator.findAppIdeModule(project) ?: return null

        val modulePath = VariantReader.getFacetModulePath(ideAppModule) ?: ":app"

        // 变体优先：Model -> Facet
        val variant = VariantReader.getSelectedVariantFromModels(ideAppModule)
            ?: VariantReader.getSelectedVariantForModule(ideAppModule)

        return if (!variant.isNullOrBlank()) VariantSelection(modulePath = modulePath, variant = variant) else null
    }
}

data class VariantSelection(
    val modulePath: String,
    val variant: String
)
