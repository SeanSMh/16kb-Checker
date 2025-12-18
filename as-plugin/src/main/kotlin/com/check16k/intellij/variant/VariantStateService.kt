package com.check16k.intellij.variant

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project 级别缓存当前 app module 的选中 variant，并提供监听。
 *
 * 目标：
 * 1) 能拿到 Model/Facet 的 selectedVariant 就用它（最准确）
 * 2) 拿不到时，也要有稳定兜底（避免一直是 debug/旧值）
 */
@Service(Service.Level.PROJECT)
class VariantStateService(private val project: Project) {

    @Volatile
    private var current: VariantSelection? = null
    val currentVariant: String? get() = current?.variant
    val currentModulePath: String? get() = current?.modulePath

    private val listeners = CopyOnWriteArrayList<(VariantSelection?) -> Unit>()
    @Volatile private var installed = false

    /**
     * 可选：由调用方提供兜底 variant（比如从 artifact 文件名推断出来的）
     * 你在 RunCheckAction 弹窗前拿到 variantGuessFromArtifact 后 set 一下就行。
     */
    @Volatile
    private var fallbackVariantProvider: (() -> String?)? = null

    fun setFallbackVariantProvider(provider: (() -> String?)?) {
        fallbackVariantProvider = provider
    }

    fun addListener(l: (VariantSelection?) -> Unit) {
        listeners += l
        l(current)
    }

    /**
     * 立即读取当前 app module 的选中 variant，并广播。
     *
     * 读不到 Model/Facet 时：使用 fallbackVariantProvider()，再不行则不更新（保留旧值）
     */
    fun refreshNow() {
        val selection = readCurrentAppSelection()
            ?: readFallbackSelection()

        // 如果这次还是拿不到，就不要把 current 置空（避免 UI 退化）
        if (selection != null) {
            current = selection
            listeners.forEach { it(selection) }
        }
    }

    fun installListenersOnce() {
        if (installed) return
        installed = true

        VariantSyncListener.install(project) { refreshNow() }
        VariantSelectionListener.install(project) { refreshNow() }

        refreshNow()
    }

    fun guessAppModulePath(): String = AppModuleLocator.findAppGradlePath(project)

    // -------------------- private --------------------

    private fun readCurrentAppSelection(): VariantSelection? {
        val ideAppModule = AppModuleLocator.findAppIdeModule(project) ?: return null
        val modulePath = VariantReader.getFacetModulePath(ideAppModule) ?: ":app"

        // 变体优先：Model -> Facet
        val variant = VariantReader.getSelectedVariantFromModels(ideAppModule)
            ?: VariantReader.getSelectedVariantForModule(ideAppModule)

        return if (!variant.isNullOrBlank()) VariantSelection(modulePath, variant) else null
    }

    private fun readFallbackSelection(): VariantSelection? {
        val modulePath = guessAppModulePath()
        val v = fallbackVariantProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return VariantSelection(modulePath, v)
    }
}

data class VariantSelection(
    val modulePath: String,
    val variant: String
)
