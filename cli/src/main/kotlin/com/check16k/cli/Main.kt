package com.check16k.cli

import com.check16k.core.ArchiveScanner
import com.check16k.core.CheckConfig
import com.check16k.core.HashOriginIndex
import com.check16k.core.ReportWriter
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = parseArgs(args) ?: return

    val config = CheckConfig(
        pageSize = options.pageSize,
        checkZipAlignment = options.checkZipAlignment,
        checkCompressed = options.checkCompressed,
        compressedAsError = options.compressedAsError,
        inferOrigin = options.inferOrigin,
        strict = options.strict
    )

    val hashOrigins = if (options.originsFile != null) {
        val content = Files.readString(options.originsFile)
        Json.decodeFromString<HashOriginIndex>(content).toMap()
    } else {
        emptyMap()
    }

    val scanner = ArchiveScanner(config)
    val report = scanner.scan(options.artifact, options.variant, hashOrigins)

    if (options.output != null) {
        ReportWriter.writeJson(report, options.output, pretty = true)
        println("JSON report written to ${options.output}")
    } else {
        println(ReportWriter.toJson(report, pretty = true))
    }

    options.mdOutput?.let {
        ReportWriter.writeMarkdown(report, it)
        println("Markdown report written to $it")
    }

    options.htmlOutput?.let {
        ReportWriter.writeHtml(report, it)
        println("HTML report written to $it")
    }

    if (config.strict && report.summary.fail > 0) {
        exitProcess(2)
    }
}

private data class CliOptions(
    val artifact: Path,
    val variant: String?,
    val originsFile: Path?,
    val output: Path?,
    val mdOutput: Path?,
    val htmlOutput: Path?,
    val pageSize: Int,
    val checkZipAlignment: Boolean,
    val checkCompressed: Boolean,
    val compressedAsError: Boolean,
    val inferOrigin: Boolean,
    val strict: Boolean
)

private fun parseArgs(args: Array<String>): CliOptions? {
    if (args.isEmpty()) {
        printUsage()
        return null
    }

    var artifact: Path? = null
    var variant: String? = null
    var originsFile: Path? = null
    var output: Path? = null
    var mdOutput: Path? = null
    var htmlOutput: Path? = null
    var autoArtifactDir: Path? = null
    var pageSize = CheckConfig.DEFAULT_PAGE_SIZE
    var checkZipAlignment = true
    var checkCompressed = true
    var compressedAsError = false
    var inferOrigin = true
    var strict = true

    val iterator = args.iterator()
    while (iterator.hasNext()) {
        when (val arg = iterator.next()) {
            "--variant" -> variant = iterator.nextOrNull("variant") ?: return null
            "--origins" -> originsFile = iterator.nextOrNull("origins file")?.let { Path.of(it) } ?: return null
            "--output" -> output = iterator.nextOrNull("output")?.let { Path.of(it) } ?: return null
            "--md-output" -> mdOutput = iterator.nextOrNull("markdown output")?.let { Path.of(it) } ?: return null
            "--html-output" -> htmlOutput = iterator.nextOrNull("html output")?.let { Path.of(it) } ?: return null
            "--auto-artifact" -> {
                val dir = iterator.nextOrNull("artifact search dir") ?: return null
                autoArtifactDir = Path.of(dir)
            }
            "--page-size" -> {
                val value = iterator.nextOrNull("page size") ?: return null
                pageSize = value.toIntOrNull() ?: run {
                    println("Invalid page size: $value")
                    return null
                }
            }
            "--no-zip-align" -> checkZipAlignment = false
            "--allow-deflate" -> checkCompressed = false
            "--compressed-error" -> compressedAsError = true
            "--no-origin" -> inferOrigin = false
            "--no-strict" -> strict = false
            "-h", "--help" -> {
                printUsage()
                return null
            }
            else -> {
                if (artifact == null) {
                    artifact = Path.of(arg)
                } else {
                    println("Unknown argument: $arg")
                    return null
                }
            }
        }
    }

    val resolvedArtifact = when {
        artifact != null -> artifact
        autoArtifactDir != null -> {
            val found = findLatestArtifact(autoArtifactDir!!)
            if (found == null) {
                println("No APK/AAB found under $autoArtifactDir")
                return null
            } else {
                println("Auto-detected artifact: $found")
                found
            }
        }
        else -> {
            println("Missing artifact path. Provide a path or use --auto-artifact <dir>.")
            printUsage()
            return null
        }
    }

    return CliOptions(
        artifact = resolvedArtifact,
        variant = variant,
        originsFile = originsFile,
        output = output,
        mdOutput = mdOutput,
        htmlOutput = htmlOutput,
        pageSize = pageSize,
        checkZipAlignment = checkZipAlignment,
        checkCompressed = checkCompressed,
        compressedAsError = compressedAsError,
        inferOrigin = inferOrigin,
        strict = strict
    )
}

private fun Iterator<String>.nextOrNull(label: String): String? {
    return if (hasNext()) next() else {
        println("Missing value for $label")
        null
    }
}

private fun printUsage() {
    println(
        """
        16KB Checker CLI
        Usage: check16k <artifact.apk|aab> [options]
        Options:
          --auto-artifact <dir>     Auto-detect latest APK/AAB under dir
          --variant <name>           Variant name for report metadata
          --origins <file>           JSON hash → origin mapping file
          --output <file>            Write report JSON to file (default: stdout)
          --md-output <file>         Write failing .so table to Markdown file
          --html-output <file>       Write HTML report
          --page-size <int>          Page size to validate (default: 16384)
          --no-zip-align             Skip ZIP data offset alignment check
          --allow-deflate            Skip compression check
          --compressed-error         Treat compressed .so as error instead of warning
          --no-origin                Do not infer origins even if mapping provided
          --no-strict                Do not exit non-zero when failures exist
          -h, --help                 Show this help
        """.trimIndent()
    )
}

private fun findLatestArtifact(dir: Path, maxDepth: Int = 6): Path? {
    return try {
        Files.walk(dir, maxDepth)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { path ->
                        val name = path.fileName.toString().lowercase()
                        name.endsWith(".apk") || name.endsWith(".aab")
                    }
                    .max { a, b ->
                        val t1 = Files.getLastModifiedTime(a).toMillis()
                        val t2 = Files.getLastModifiedTime(b).toMillis()
                        t1.compareTo(t2)
                    }
                    .orElse(null)
            }
    } catch (_: Throwable) {
        null
    }
}
