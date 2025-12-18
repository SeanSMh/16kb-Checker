package com.check16k.intellij.variant

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

object AppModuleLocator {

    /**
     * 尽量找到“app module”的 IDE Module（module.name 往往是 xxx.app）
     * 优先：
     * 1) facet.getModulePath()==":app"
     * 2) module.name=="app"
     * 3) module.name.endsWith(".app")
     */
    fun findAppIdeModule(project: Project): Module? {
        val modules = ModuleManager.getInstance(project).modules

        // 1) Facet modulePath 精确 = :app
        modules.firstOrNull { m ->
            VariantReader.getFacetModulePath(m) == ":app"
        }?.let { return it }

        // 2) 名称 app
        modules.firstOrNull { it.name == "app" }?.let { return it }

        // 3) 结尾 .app
        modules.firstOrNull { it.name.endsWith(".app") }?.let { return it }

        return null
    }

    /**
     * 返回 GradlePath（:app），如果拿不到就返回 ":app"
     */
    fun findAppGradlePath(project: Project): String {
        val m = findAppIdeModule(project) ?: return ":app"
        return VariantReader.getFacetModulePath(m) ?: ":app"
    }
}
