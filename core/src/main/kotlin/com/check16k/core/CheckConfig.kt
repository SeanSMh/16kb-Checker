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
    val strict: Boolean = true
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 16_384
    }
}
