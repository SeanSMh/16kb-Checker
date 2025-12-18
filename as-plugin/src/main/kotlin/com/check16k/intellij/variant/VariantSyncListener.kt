package com.check16k.intellij.variant

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

object VariantSyncListener {
    fun install(project: Project, onSync: () -> Unit) {
        // 尝试通过消息总线监听 Gradle Sync Topic（反射，避免编译期依赖）
        try {
            val topicClass = Class.forName(
                "com.android.tools.idea.gradle.project.sync.GradleSyncListener",
                false,
                VariantSyncListener::class.java.classLoader
            )
            val syncStateClass = Class.forName(
                "com.android.tools.idea.gradle.project.sync.GradleSyncState",
                false,
                VariantSyncListener::class.java.classLoader
            )
            val topicField = syncStateClass.getDeclaredField("GRADLE_SYNC_TOPIC")
            topicField.isAccessible = true
            val topic = topicField.get(null) as? Topic<Any>
            if (topic != null) {
                val connection = project.messageBus.connect(project)
                connection.subscribe(topic, java.lang.reflect.Proxy.newProxyInstance(
                    VariantSyncListener::class.java.classLoader,
                    arrayOf(topicClass)
                ) { _, method, _ ->
                    val name = method.name.lowercase()
                    if (name.contains("sync")) onSync()
                    null
                })
                return
            }
        } catch (_: Throwable) {
            // ignore
        }

        // 兜底：如果 Topic 反射失败，暂不处理；Action 点击时会 refreshNow
    }
}
