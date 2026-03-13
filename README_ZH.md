# 16KB Checker

基于 Kotlin 的 16KB `.so` 检测工具链，包含：

- `core`：APK/AAB 扫描、ELF 校验、ZIP 对齐检查、报告模型
- `gradle-plugin`：为每个 Android variant 注册 `check16k<Variant>` 任务，收集依赖 `.so` 来源并生成报告
- `cli`：离线命令行扫描入口

## 快速开始

### Gradle 插件

在 Android 工程的 `build.gradle`（或 `build.gradle.kts`）中应用：

```kotlin
plugins {
    id("com.check16k.plugin")
}

check16k {
    pageSize.set(16_384)
    strict.set(true)
    checkZipAlignment.set(true)
    checkCompressed.set(true)
    compressedAsError.set(false)
    inferOrigin.set(true)
    reportDir.set(layout.projectDirectory.dir("check-result")) // 默认已指向 check-result
    artifactType.set(ArtifactTypePreference.AUTO) // 可选: AUTO / APK / BUNDLE
}
```

执行：

```bash
./gradlew check16kDebug
./gradlew check16kRelease
```

输出：`check-result/<variant>.json`（默认目录，可改），`strict=true` 时有 FAIL 会让任务失败。

### CLI

```bash
./gradlew :cli:run --args "app-release.apk --origins build/intermediates/check16k/release/hash-origins.json --output report.json"
```

常用参数：

- `--variant <name>`：报告中写入的 variant 名
- `--origins <file>`：hash→origin 映射 JSON（Gradle 任务生成）
- `--page-size <int>`：默认 16384
- `--no-zip-align` / `--allow-deflate` / `--compressed-error`
- `--no-origin`：忽略来源推断
- `--no-strict`：不因 FAIL 退出非零状态

## 模块划分

- `core`：`ArchiveScanner` 读取 ZIP central directory 计算 data offset，对 ELF Program Header 做 16KB 判定，输出 `ScanReport`
- `gradle-plugin`：`CollectSoOriginsTask` 解包 AAR、扫描 `jniLibs` 生成 hash→origin 映射；`Check16kTask` 消费 APK/AAB 产物生成报告并可 fail 构建
- `cli`：轻量参数解析，串联 `ArchiveScanner` 与报告输出
