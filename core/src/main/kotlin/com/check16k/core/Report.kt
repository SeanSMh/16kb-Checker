package com.check16k.core

import kotlinx.serialization.Serializable

@Serializable
data class ScanItem(
    val path: String,
    val abi: String?,
    val soName: String,
    val sha256: String,
    val issues: List<Issue>,
    val origin: List<OriginMatch> = emptyList(),
    val suggest: List<String> = emptyList()
)

@Serializable
data class ReportSummary(
    val totalSo: Int,
    val fail: Int,
    val warn: Int
)

@Serializable
data class ScanReport(
    val artifact: String,
    val variant: String? = null,
    val summary: ReportSummary,
    val items: List<ScanItem>
) {
    companion object {
        fun from(artifact: String, variant: String?, items: List<ScanItem>): ScanReport {
            val failItems = items.count { item -> item.issues.any { it.severity == Severity.FAIL } }
            val warnItems = items.count { item ->
                item.issues.any { it.severity == Severity.WARN } && item.issues.none { it.severity == Severity.FAIL }
            }
            return ScanReport(
                artifact = artifact,
                variant = variant,
                summary = ReportSummary(
                    totalSo = items.size,
                    fail = failItems,
                    warn = warnItems
                ),
                items = items
            )
        }
    }
}
