package com.check16k.intellij

object InitScriptProvider {

    private const val RESOURCE_PATH = "/check16k/script.gradle"

    fun buildInitScript(
        outputJsonAbsPath: String,
        modulePath: String,
        variantName: String,
        abiFilter: String
    ): String {
        val template = loadInitScriptTemplate()
        return template
            .replace("__OUT_JSON_ABS_PATH__", escapeForGroovySingleQuoted(outputJsonAbsPath))
            .replace("__TARGET_MODULE__", escapeForGroovySingleQuoted(modulePath))
            .replace("__VARIANT__", escapeForGroovySingleQuoted(variantName))
            .replace("__ABI_FILTER__", escapeForGroovySingleQuoted(abiFilter))
    }

    private fun loadInitScriptTemplate(): String {
        val url = requireNotNull(InitScriptProvider::class.java.getResource(RESOURCE_PATH)) {
            "Missing resource: $RESOURCE_PATH (expect in as-plugin/src/main/resources/check16k/script.gradle)"
        }
        return url.readTextUtf8()
    }

    // 模板里用单引号包裹：'...'
    private fun escapeForGroovySingleQuoted(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")
}

private fun java.net.URL.readTextUtf8(): String =
    openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
