package com.check16k.core

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ArchiveScanner(private val config: CheckConfig = CheckConfig()) {
    private val apkPattern = Regex("^lib/([^/]+)/(.+\\.so)$")
    private val aabPattern = Regex("^(.*?)/lib/([^/]+)/(.+\\.so)$")

    fun scan(
        artifact: Path,
        variant: String? = null,
        hashOrigins: Map<String, List<Origin>> = emptyMap(),
        abiFilter: Set<String> = emptySet()
    ): ScanReport {
        val archivePath = artifact.toAbsolutePath().toString()
        val entryInfo = ZipCentralDirectory.readEntries(archivePath)
        val items = mutableListOf<ScanItem>()

        ZipFile(archivePath).use { zip ->
            val infos = if (entryInfo.isNotEmpty()) {
                entryInfo.values
            } else {
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { fallbackEntryInfo(it) }
                    .toList()
            }

            infos.forEach { info ->
                val match = matchSo(info.name) ?: return@forEach
                if (abiFilter.isNotEmpty() && !abiFilter.contains(match.abi)) return@forEach
                val isStrictSo = config.strictSoNames.contains(match.soName)
                val entry = zip.getEntry(info.name) ?: return@forEach
                val bytes = zip.getInputStream(entry).use { it.readAllBytesSafe() }
                val sha256 = Hashing.sha256(bytes)

                val issues = mutableListOf<Issue>()
                issues += ElfAnalyzer.analyze(bytes, config.pageSize)

                if (config.checkZipAlignment && info.localHeaderOffset > 0) {
                    val offset = info.dataOffset
                    val mod = offset % config.pageSize
                    if (mod != 0L) {
                        issues += Issue(
                            type = IssueType.ZIP_ALIGN,
                            detail = "dataOffset%${config.pageSize}=$mod (offset=$offset)",
                            severity = Severity.FAIL
                        )
                    }
                }

                if (config.checkCompressed && info.compressionMethod != ZipEntry.STORED) {
                    val severity = if (config.compressedAsError) Severity.FAIL else Severity.WARN
                    val method = info.compressionMethod
                    issues += Issue(
                        type = IssueType.ZIP_COMPRESSED,
                        detail = "compressionMethod=$method (expected STORED=0)",
                        severity = severity
                    )
                }

                val origins = if (config.inferOrigin) {
                    hashOrigins[sha256].orEmpty().map { OriginMatch(it, confidence = 1.0) }
                } else emptyList()

                val normalizedIssues = if (isStrictSo) {
                    issues.map { issue ->
                        if (issue.severity == Severity.WARN) {
                            issue.copy(severity = Severity.FAIL)
                        } else {
                            issue
                        }
                    }
                } else {
                    issues
                }

                val risks = RiskEvaluator.risksFor(normalizedIssues)
                val suggestions = buildSuggestions(normalizedIssues)

                items += ScanItem(
                    path = info.name,
                    abi = match.abi,
                    soName = match.soName,
                    sha256 = sha256,
                    issues = normalizedIssues,
                    risk = risks,
                    origin = origins,
                    suggest = suggestions
                )
            }
        }

        return ScanReport.from(
            artifact = artifact.fileName.toString(),
            variant = variant,
            items = items.sortedBy { it.path }
        )
    }

    private fun buildSuggestions(issues: List<Issue>): List<String> {
        val suggestions = mutableListOf<String>()
        if (issues.any { it.type == IssueType.ELF_ALIGN && it.severity == Severity.FAIL }) {
            suggestions += "升级或重建该 .so 以支持 16KB page size"
        }
        if (issues.any { it.type == IssueType.ZIP_ALIGN }) {
            suggestions += "调整打包步骤，确保 ZIP data offset 按 16KB 对齐"
        }
        if (issues.any { it.type == IssueType.ZIP_COMPRESSED }) {
            suggestions += "改为存储模式打包 .so（compression=STORED）"
        }
        return suggestions.distinct()
    }

    private fun fallbackEntryInfo(entry: ZipEntry): ZipEntryInfo {
        val name = entry.name
        val extraLength = entry.extra?.size ?: 0
        return ZipEntryInfo(
            name = name,
            compressionMethod = entry.method,
            localHeaderOffset = 0,
            fileNameLength = name.length,
            extraFieldLength = extraLength
        )
    }

    private fun matchSo(path: String): SoMatch? {
        apkPattern.matchEntire(path)?.let { m ->
            return SoMatch(module = null, abi = m.groupValues[1], soName = m.groupValues[2])
        }
        aabPattern.matchEntire(path)?.let { m ->
            return SoMatch(module = m.groupValues[1], abi = m.groupValues[2], soName = m.groupValues[3])
        }
        return null
    }

    private fun InputStream.readAllBytesSafe(): ByteArray {
        return buffered().use { it.readBytes() }
    }

    private data class SoMatch(
        val module: String?,
        val abi: String,
        val soName: String
    )
}
