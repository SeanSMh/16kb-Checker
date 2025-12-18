package com.check16k.intellij.variant

import com.intellij.openapi.project.Project

/**
 * 兼容你 VariantStateService 里引用的 getSelectedAppVariant()
 * 返回当前 app module 的 selectedVariantName（拿不到返回 null）
 */
fun Project.getSelectedAppVariant(): String? {
    val appModule = AppModuleLocator.findAppIdeModule(this) ?: return null
    // 优先 Model，再 Facet
    return VariantReader.getSelectedVariantFromModels(appModule)
        ?: VariantReader.getSelectedVariantForModule(appModule)
}
