package com.check16k.intellij.variant

import com.intellij.openapi.project.Project
import com.check16k.intellij.variant.AppModuleLocator

/**
 * 尝试监听 Build Variant 选择变化（若当前 AS 版本提供该类）。
 * 如不可用则静默忽略，用户点击 Action 时 refreshNow() 兜底。
 */
object VariantSelectionListener {
    fun install(project: Project, onChange: () -> Unit) {
        runCatching {
            val viewClass = Class.forName(
                "com.android.tools.idea.gradle.variant.view.BuildVariantView",
                false,
                VariantSelectionListener::class.java.classLoader
            )
            val listenerClass = Class.forName(
                "com.android.tools.idea.gradle.variant.view.BuildVariantView\$BuildVariantSelectionChangeListener",
                false,
                VariantSelectionListener::class.java.classLoader
            )
            val getInstance = viewClass.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.size == 1 }
                ?: return
            val addListener = viewClass.methods.firstOrNull { it.name == "addListener" }
                ?: return
            val view = getInstance.invoke(null, project) ?: return
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                VariantSelectionListener::class.java.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name.contains("buildVariantSelected", true)) {
                    // 如果能拿到 facet，再判断是否 app 模块
                    val facet = args?.firstOrNull()
                    val app = AppModuleLocator.findAppIdeModule(project)
                    val facetModule = runCatching {
                        facet?.javaClass?.methods?.firstOrNull { it.name == "getModule" }?.invoke(facet)
                    }.getOrNull()
                    if (app == null || facetModule == app) onChange()
                }
                null
            }
            addListener.invoke(view, proxy)
        }.onFailure {
            // ignore
        }
    }
}
