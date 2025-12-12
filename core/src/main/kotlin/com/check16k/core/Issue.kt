package com.check16k.core

import kotlinx.serialization.Serializable

@Serializable
enum class IssueType {
    ELF_ALIGN,
    ELF_FORMAT,
    ZIP_ALIGN,
    ZIP_COMPRESSED
}

@Serializable
enum class Severity {
    FAIL,
    WARN
}

@Serializable
data class Issue(
    val type: IssueType,
    val detail: String,
    val severity: Severity
)
