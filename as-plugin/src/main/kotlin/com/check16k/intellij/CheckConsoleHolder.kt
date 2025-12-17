package com.check16k.intellij

import com.intellij.execution.ui.ConsoleView

/**
 * 方案B：ToolWindow 里创建 ConsoleView 后，保存到这里，供 RunCheckAction 实时写日志。
 */
object CheckConsoleHolder {
    @Volatile
    var console: ConsoleView? = null
}
