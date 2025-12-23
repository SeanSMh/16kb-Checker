package com.check16k.core

object RiskEvaluator {
    fun risksFor(issues: List<Issue>): List<String> {
        return issues.mapNotNull { riskFor(it) }.distinct()
    }

    fun risksFor(issues: List<Issue>, severity: Severity): List<String> {
        return issues
            .filter { it.severity == severity }
            .mapNotNull { riskFor(it) }
            .distinct()
    }

    private fun riskFor(issue: Issue): String? {
        return when (issue.type) {
            IssueType.ELF_ALIGN ->
                "ELF PT_LOAD alignment may fail on 16KB devices (dlopen failure)"
            IssueType.ELF_FORMAT ->
                "Invalid ELF format; loader will reject (dlopen failure)"
            IssueType.ZIP_ALIGN ->
                "ZIP data offset not 16KB-aligned; direct mmap may fail"
            IssueType.ZIP_COMPRESSED ->
                "Compressed .so cannot be mmap'd; may fail when extractNativeLibs=false"
        }
    }
}
