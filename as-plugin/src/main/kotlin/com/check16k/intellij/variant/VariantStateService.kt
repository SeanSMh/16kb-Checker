package com.check16k.intellij.variant

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project 级别缓存当前 app module 的选中 variant，并提供监听。
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
     * 立即读取当前 app module 的选中 variant（Facet -> IdeaAndroidProject），并广播。
     */
    fun refreshNow() {
        val selection = VariantReader.getSelectedAppVariant(project)
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
        VariantSelectionListener.install(project) {
            refreshNow()
        }

        // 首次读取
        refreshNow()
    }

    /**
     * 如果没有 facet/模型，兜底返回模块名叫 app 的 modulePath。
     */
    fun guessAppModulePath(): String? {
        val mm = ModuleManager.getInstance(project)
        return mm.findModuleByName(AppModuleLocator.APP_MODULE_NAME)?.let { ":" + it.name }
            ?: mm.modules.firstOrNull { it.name == AppModuleLocator.APP_MODULE_NAME }?.let { ":" + it.name }
    }
}

data class VariantSelection(
    val modulePath: String,
    val variant: String
)
