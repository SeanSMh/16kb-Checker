package com.check16k.intellij.variant

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

object VariantReader {

    /**
     * 最直接：AndroidFacet -> IdeaAndroidProject 读取当前选中 variant。
     */
    fun getSelectedVariantFromFacet(module: Module): String? {
        val facetClass = runCatching {
            Class.forName("org.jetbrains.android.facet.AndroidFacet", false, VariantReader::class.java.classLoader)
        }.getOrNull() ?: return null
        val getInstance = facetClass.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.size == 1 }
            ?: return null
        val facet = runCatching { getInstance.invoke(null, module) }.getOrNull() ?: return null

        // ideaAndroidProject.selectedVariantName
        runCatching {
            val ideaProject = facet.javaClass.methods.firstOrNull { it.name == "getIdeaAndroidProject" }?.invoke(facet)
            ideaProject?.javaClass?.methods?.firstOrNull { it.name == "getSelectedVariantName" }?.invoke(ideaProject) as? String
        }.getOrNull()?.let { if (!it.isNullOrBlank()) return it }

        // Facet 直接暴露 selected variant 的 getter
        runCatching {
            facet.javaClass.methods.firstOrNull { it.name.contains("SelectedVariant", true) }?.invoke(facet) as? String
        }.getOrNull()?.let { if (!it.isNullOrBlank()) return it }

        return null
    }

    fun getSelectedAppVariant(project: Project): VariantSelection? {
        val appModule = AppModuleLocator.findAppModule(project) ?: return null
        val variant = getSelectedVariantFromFacet(appModule) ?: return null
        val modulePath = normalizeModulePath(appModule.name)
        return VariantSelection(modulePath = modulePath, variant = variant)
    }

    private fun normalizeModulePath(name: String): String =
        if (name.startsWith(":")) name else ":" + name
}

/**
 * 定位 app module。默认名 "app"，若有多 app 请调整 APP_MODULE_NAME。
 */
object AppModuleLocator {
    const val APP_MODULE_NAME = "app"

    fun findAppModule(project: Project): Module? {
        val mm = ModuleManager.getInstance(project)
        return mm.findModuleByName(APP_MODULE_NAME)
            ?: mm.modules.firstOrNull { it.name == APP_MODULE_NAME }
            ?: mm.modules.firstOrNull { it.name.lowercase() == APP_MODULE_NAME }
    }
}
