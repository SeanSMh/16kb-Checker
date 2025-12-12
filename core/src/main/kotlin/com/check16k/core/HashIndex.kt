package com.check16k.core

import kotlinx.serialization.Serializable

@Serializable
data class HashOriginEntry(
    val sha256: String,
    val abi: String? = null,
    val soName: String? = null,
    val origin: Origin
)

@Serializable
data class HashOriginIndex(
    val items: List<HashOriginEntry>
) {
    fun toMap(): Map<String, List<Origin>> {
        return items.groupBy { it.sha256 }.mapValues { (_, entries) ->
            entries.map { it.origin }
        }
    }
}
