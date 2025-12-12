package com.check16k.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object ElfAnalyzer {
    private const val ELF_MAGIC: Int = 0x7f454c46 // 0x7F 'E' 'L' 'F'
    private const val ELFCLASS32: Int = 1
    private const val ELFCLASS64: Int = 2
    private const val ELFDATA2LSB: Int = 1
    private const val ELFDATA2MSB: Int = 2
    private const val PT_LOAD: Long = 1L

    fun analyze(bytes: ByteArray, pageSize: Int): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (!isElf(bytes)) {
            issues += Issue(
                type = IssueType.ELF_FORMAT,
                detail = "Missing ELF magic",
                severity = Severity.FAIL
            )
            return issues
        }

        val clazz = bytes[4].toInt() and 0xFF
        val is64 = when (clazz) {
            ELFCLASS32 -> false
            ELFCLASS64 -> true
            else -> {
                issues += Issue(
                    IssueType.ELF_FORMAT,
                    detail = "Unsupported ELF class: $clazz",
                    severity = Severity.FAIL
                )
                return issues
            }
        }

        val data = bytes[5].toInt() and 0xFF
        val order = when (data) {
            ELFDATA2LSB -> ByteOrder.LITTLE_ENDIAN
            ELFDATA2MSB -> ByteOrder.BIG_ENDIAN
            else -> ByteOrder.LITTLE_ENDIAN
        }

        val minHeader = if (is64) 64 else 52
        if (bytes.size < minHeader) {
            issues += Issue(
                IssueType.ELF_FORMAT,
                detail = "ELF header too small: ${bytes.size} bytes",
                severity = Severity.FAIL
            )
            return issues
        }

        val buffer = ByteBuffer.wrap(bytes).order(order)
        val phoff = if (is64) readLong(buffer, 32) else readUInt(buffer, 28)
        val phentsize = readUShort(buffer, if (is64) 54 else 42)
        val phnum = readUShort(buffer, if (is64) 56 else 44)

        if (phnum == 0 || phentsize == 0) {
            issues += Issue(
                IssueType.ELF_FORMAT,
                detail = "No program headers found",
                severity = Severity.FAIL
            )
            return issues
        }

        val maxOffset = phoff + phentsize.toLong() * phnum.toLong()
        if (maxOffset > bytes.size) {
            issues += Issue(
                IssueType.ELF_FORMAT,
                detail = "Program header table truncated (end=$maxOffset, size=${bytes.size})",
                severity = Severity.FAIL
            )
            return issues
        }

        for (index in 0 until phnum) {
            val entryOffset = phoff + phentsize.toLong() * index.toLong()
            val entry = slice(buffer, entryOffset, phentsize)
            val pType = readUInt(entry, 0)
            if (pType != PT_LOAD) continue

            val pOffset = if (is64) readLong(entry, 8) else readUInt(entry, 4)
            val pVaddr = if (is64) readLong(entry, 16) else readUInt(entry, 8)
            val pAlign = if (is64) readLong(entry, 48) else readUInt(entry, 28)

            if (pAlign < pageSize) {
                issues += Issue(
                    type = IssueType.ELF_ALIGN,
                    detail = "p_align=$pAlign < pageSize=$pageSize",
                    severity = Severity.FAIL
                )
            }

            val vaddrMod = pVaddr % pageSize
            val offsetMod = pOffset % pageSize
            if (vaddrMod != offsetMod) {
                issues += Issue(
                    type = IssueType.ELF_ALIGN,
                    detail = "p_vaddr%=$vaddrMod differs from p_offset%=$offsetMod (pageSize=$pageSize)",
                    severity = Severity.FAIL
                )
            }

            if (offsetMod != 0L) {
                issues += Issue(
                    type = IssueType.ELF_ALIGN,
                    detail = "p_offset%pageSize=$offsetMod (expected 0)",
                    severity = Severity.WARN
                )
            }
        }

        return issues
    }

    private fun isElf(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
        return magic == ELF_MAGIC
    }

    private fun readUShort(buffer: ByteBuffer, offset: Int): Int {
        return buffer.getShort(offset).toInt() and 0xFFFF
    }

    private fun readUInt(buffer: ByteBuffer, offset: Int): Long {
        return buffer.getInt(offset).toLong() and 0xFFFF_FFFFL
    }

    private fun readLong(buffer: ByteBuffer, offset: Int): Long {
        return buffer.getLong(offset)
    }

    private fun slice(buffer: ByteBuffer, offset: Long, length: Int): ByteBuffer {
        val safeOffset = min(offset, buffer.limit().toLong()).toInt()
        val safeLength = min(length, buffer.limit() - safeOffset)
        val duplicate = buffer.duplicate()
        duplicate.position(safeOffset)
        duplicate.limit(safeOffset + safeLength)
        return duplicate.slice().order(buffer.order())
    }
}
