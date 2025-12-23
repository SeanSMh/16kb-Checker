package com.check16k.core

/**
 * Configuration for a scan.
 */
data class CheckConfig(
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val checkZipAlignment: Boolean = true,
    val checkCompressed: Boolean = true,
    val compressedAsError: Boolean = false,
    val inferOrigin: Boolean = true,
    val strict: Boolean = true,
    val strictSoNames: Set<String> = DEFAULT_STRICT_SO_NAMES
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 16_384
        val DEFAULT_STRICT_SO_NAMES: Set<String> = setOf("libc++_shared.so")
    }
}
