package com.check16k.intellij.variant

import com.intellij.openapi.module.Module

object VariantReader {

    /**
     * 读取 Build Variants 面板“当前选中 variant”
     * 走 AndroidFacet -> IdeaAndroidProject.selectedVariantName（反射）
     */
    fun getSelectedVariantForModule(module: Module): String? {
        val ideaAndroidProject = getIdeaAndroidProject(module) ?: return null
        return invokeStringGetter(ideaAndroidProject, "getSelectedVariantName")
    }

    /**
     * 尝试从 Android Gradle Model 读取 selectedVariant（不同版本可能有不同类名）
     * - 优先 getSelectedVariantName
     * - 再退回 getSelectedVariant().getName()
     */
    fun getSelectedVariantFromModels(module: Module): String? {
        val model = getGradleAndroidModel(module) ?: return null

        invokeStringGetter(model, "getSelectedVariantName")?.let { return it }

        // getSelectedVariant().getName()
        val selectedVariant = runCatching { model.javaClass.methods.firstOrNull { it.name == "getSelectedVariant" && it.parameterCount == 0 }?.invoke(model) }
            .getOrNull() ?: return null
        return invokeStringGetter(selectedVariant, "getName")
    }

    /**
     * ✅ 供 RunCheckAction 精确匹配 GradlePath 用
     * 从 AndroidFacet 里反射取 modulePath / gradlePath（不同版本方法名可能不同）
     */
    fun getFacetModulePath(module: Module): String? {
        val facet = getAndroidFacet(module) ?: return null
        // 常见方法名：getModulePath / getGradlePath
        return invokeStringGetter(facet, "getModulePath")
            ?: invokeStringGetter(facet, "getGradlePath")
    }

    // ---------------- private ----------------

    private fun getAndroidFacet(module: Module): Any? {
        val facetClass = runCatching {
            Class.forName("org.jetbrains.android.facet.AndroidFacet", false, VariantReader::class.java.classLoader)
        }.getOrNull() ?: return null

        // AndroidFacet.getInstance(Module)
        return runCatching {
            val getInstance = facetClass.methods.firstOrNull {
                it.name == "getInstance" && it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "com.intellij.openapi.module.Module"
            } ?: return@runCatching null
            getInstance.invoke(null, module)
        }.getOrNull()
    }

    private fun getIdeaAndroidProject(module: Module): Any? {
        val facet = getAndroidFacet(module) ?: return null
        // facet.getIdeaAndroidProject()
        return runCatching {
            facet.javaClass.methods.firstOrNull { it.name == "getIdeaAndroidProject" && it.parameterCount == 0 }
                ?.invoke(facet)
        }.getOrNull()
    }

    private fun getGradleAndroidModel(module: Module): Any? {
    // 这几个是不同 AS 版本的常见位置，尽量全覆盖
    val candidates = listOf(
        "com.android.tools.idea.gradle.project.model.AndroidModuleModel",
        "com.android.tools.idea.gradle.project.model.GradleAndroidModel",
        "com.android.tools.idea.projectsystem.gradle.GradleAndroidModel",
        "com.android.tools.idea.projectsystem.gradle.AndroidModuleModel",
        "com.android.tools.idea.projectsystem.gradle.GradleAndroidModuleModel"
    )

    for (cn in candidates) {
        val clazz = runCatching { Class.forName(cn, false, VariantReader::class.java.classLoader) }.getOrNull() ?: continue
        val getMethod = clazz.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 1 &&
                it.parameterTypes[0].name == "com.intellij.openapi.module.Module"
        } ?: continue

        val model = runCatching { getMethod.invoke(null, module) }.getOrNull()
        if (model != null) return model
    }
    return null
}


    private fun invokeStringGetter(target: Any, methodName: String): String? {
        return runCatching {
            val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return@runCatching null
            (m.invoke(target) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
