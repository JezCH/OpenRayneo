// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Raw-copy-v1: reconstruct the exact pre-signed target, never unpack or re-sign an APK.
 * Big-endian lengths. COPY=0(offset,length), DATA=1(length,bytes), END=255.
 * Caller verifies original, patch and final SHA-256 and Android signing identity. */
internal object ApkDelta {
    fun restore(base: File, patch: File, target: File, expectedSize: Long, cancelled: () -> Unit) {
        require(expectedSize in 1..(1900L * 1024 * 1024) && base != target && patch != target)
        RandomAccessFile(base, "r").use { old ->
            DataInputStream(BufferedInputStream(patch.inputStream())).use { input ->
                val magic = ByteArray(8); input.readFully(magic)
                check(magic.contentEquals("ORDELTA1".toByteArray(Charsets.US_ASCII))) { "Invalid patch format" }
                check(input.readLong() == old.length() && input.readLong() == expectedSize) { "Patch size mismatch" }
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(65536)
                    var written = 0L
                    var commands = 0
                    while (true) {
                        cancelled()
                        val op = input.readUnsignedByte()
                        if (op == 255) break
                        check(++commands <= 200_000 && op in 0..1) { "Invalid patch command" }
                        val offset = if (op == 0) input.readLong() else 0L
                        val length = input.readLong()
                        check(length > 0 && length <= expectedSize - written) { "Patch output overflow" }
                        if (op == 0) {
                            check(offset >= 0 && offset <= old.length() && length <= old.length() - offset) { "Patch source overflow" }
                            old.seek(offset)
                        }
                        var left = length
                        while (left > 0) {
                            cancelled()
                            val count = minOf(left, buffer.size.toLong()).toInt()
                            if (op == 0) old.readFully(buffer, 0, count) else input.readFully(buffer, 0, count)
                            out.write(buffer, 0, count)
                            left -= count; written += count
                        }
                    }
                    check(written == expectedSize && input.read() == -1) { "Incomplete or trailing patch bytes" }
                    out.fd.sync()
                }
            }
        }
    }
}
