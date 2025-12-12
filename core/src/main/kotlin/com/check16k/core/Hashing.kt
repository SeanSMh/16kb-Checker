package com.check16k.core

import java.security.MessageDigest

object Hashing {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
