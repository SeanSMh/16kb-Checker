package com.check16k.core

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

data class ZipEntryInfo(
    val name: String,
    val compressionMethod: Int,
    val localHeaderOffset: Long,
    val fileNameLength: Int,
    val extraFieldLength: Int
) {
    val dataOffset: Long
        get() = localHeaderOffset + 30 + fileNameLength + extraFieldLength
}

/**
 * Minimal ZIP central directory reader to get offsets required for data alignment checks.
 */
object ZipCentralDirectory {
    private const val EOCD_SIGNATURE: Long = 0x06054b50
    private const val CEN_HEADER_SIGNATURE: Long = 0x02014b50
    private const val MAX_EOCD_SEARCH = 65_536 + 22 // spec: comment length up to 64k
    private val UTF8: Charset = Charsets.UTF_8

    fun readEntries(filePath: String): Map<String, ZipEntryInfo> {
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val eocdOffset = locateEocd(raf)
                if (eocdOffset < 0) return emptyMap()

                raf.seek(eocdOffset + 12)
                val centralDirSize = readUInt32(raf)
                val centralDirOffset = readUInt32(raf)

                val entries = mutableMapOf<String, ZipEntryInfo>()
                var cursor = centralDirOffset
                val end = centralDirOffset + centralDirSize
                while (cursor < end) {
                    raf.seek(cursor)
                    val signature = readUInt32(raf)
                    if (signature != CEN_HEADER_SIGNATURE) break

                    raf.skipBytes(4) // version made by + version needed
                    raf.skipBytes(2) // flags
                    val compressionMethod = readUInt16(raf)
                    raf.skipBytes(16) // time/date/crc/compSize/uncompSize
                    val fileNameLength = readUInt16(raf)
                    val extraFieldLength = readUInt16(raf)
                    val commentLength = readUInt16(raf)
                    raf.skipBytes(8) // disk/internal/external attrs
                    val localHeaderOffset = readUInt32(raf)
                    val nameBytes = ByteArray(fileNameLength)
                    raf.readFully(nameBytes)
                    val name = String(nameBytes, UTF8)
                    // skip extra + comment
                    raf.skipBytes(extraFieldLength + commentLength)

                    entries[name] = ZipEntryInfo(
                        name = name,
                        compressionMethod = compressionMethod,
                        localHeaderOffset = localHeaderOffset,
                        fileNameLength = fileNameLength,
                        extraFieldLength = extraFieldLength
                    )
                    cursor = raf.filePointer
                }
                entries
            }
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun locateEocd(raf: RandomAccessFile): Long {
        val fileLength = raf.length()
        val searchSize = minOf(fileLength, MAX_EOCD_SEARCH.toLong()).toInt()
        val buffer = ByteArray(searchSize)

        raf.seek(fileLength - searchSize)
        raf.readFully(buffer)

        for (i in searchSize - 22 downTo 0) {
            if (readUInt32(buffer, i) == EOCD_SIGNATURE) {
                return fileLength - searchSize + i
            }
        }
        return -1
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)).toLong() and 0xFFFF_FFFFL
    }

    private fun readUInt32(raf: RandomAccessFile): Long {
        return raf.readInt().toLong() and 0xFFFF_FFFFL
    }

    private fun readUInt16(raf: RandomAccessFile): Int {
        return raf.readShort().toInt() and 0xFFFF
    }
}
