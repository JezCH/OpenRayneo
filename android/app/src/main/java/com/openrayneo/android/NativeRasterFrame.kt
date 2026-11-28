// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/**
 * Stock 1.0.6.11 raster wire contract, not a live navigation session.
 * Parser 0x1076073C, envelope 0x105C4038, reply builder 0x105C55B4,
 * runtime 0x10751F78 (Rayneo-Original runs 46126673732/46126706170).
 * The checksum algorithm/scope for the raster assembler has not been independently
 * established. The caller must supply a verified whole-frame checksum; do not
 * send a candidate CRC or invent a route/view identity on the production link.
 */
internal object NativeRasterFrame {
    enum class View { MINI, OVERVIEW }

    internal class Key(
        val route: NativeRouteScene.Identity,
        val viewGeneration: Long,
        val frameSequence: Long
    ) {
        init {
            require(viewGeneration in 1..0xffffffffL && frameSequence in 1..0xffffffffL)
        }
        fun json() = route.json().put("view_generation", viewGeneration)
            .put("frame_seq", frameSequence)
        override fun toString() = "Key(redacted)"
    }

    internal class Fragment(val header: String, private val bytes: ByteArray) {
        // PbMsg tag 4 is binary data; omitted tag 5/6 encode proto3 zero.
        fun frame(transportSequence: Int): ByteArray {
            require(transportSequence in 0..65535)
            return ProtocolCodec.display(0x20, transportSequence, 0x35, header, bytes)
        }
        override fun toString() = "RasterFragment(bytes=${bytes.size})"
    }

    internal sealed interface Reply {
        data object Committed : Reply
        data class Rejected(val code: Long) : Reply
    }

    internal class Encoded(
        val fragments: List<Fragment>, private val key: Key, private val crc: Long
    ) {
        // No success inferred from transport ACK or GLASS_READY. A correlated
        // 0x36/code0 is an assembly/decode commit, not physical HUD visibility.
        fun reply(message: ProtocolCodec.Message): Reply? {
            if (message.version != 1 || message.type !in setOf(0x36, 0x37) ||
                message.mode != 1L || message.sequence != 0L ||
                message.audio?.isNotEmpty() == true) return null
            val json = message.json ?: return null
            if (json.length !in 1..8192) return null
            val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (body.opt("sid") != key.route.sid ||
                body.opt("route_token") != key.route.token ||
                uint(body, "route_revision") != key.route.revision ||
                uint(body, "route_generation") != key.route.generation ||
                uint(body, "view_generation") != key.viewGeneration ||
                uint(body, "frame_seq") != key.frameSequence ||
                uint(body, "crc32") != crc) return null
            val code = uint(body, "code") ?: return null
            return when {
                message.type == 0x36 && code == 0L -> Reply.Committed
                message.type == 0x37 && code > 0L -> Reply.Rejected(code)
                else -> null
            }
        }
        override fun toString() = "EncodedRaster(fragments=${fragments.size})"
    }

    private fun uint(body: JSONObject, field: String): Long? = when (val v = body.opt(field)) {
        is Int -> v.toLong().takeIf { it >= 0 }
        is Long -> v.takeIf { it in 0..0xffffffffL }
        else -> null
    }

    // Check the actual PNG dimensions, not a guessed width/height in the header.
    // Full decoding is performed by the rendering/producer layer and the peer.
    private fun dimensionsMatch(png: ByteArray, width: Int, height: Int): Boolean {
        if (png.size < 33 || !png.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return false
        fun intAt(offset: Int): Int = (0..3).fold(0) { n, i ->
            (n shl 8) or (png[offset + i].toInt() and 255)
        }
        return intAt(8) == 13 && png.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII)) &&
            intAt(16) == width && intAt(20) == height
    }

    /** Wire-only construction. Requires positively negotiated limits, caller
     * supplied route/view identity and a separately verified frame checksum.
     * No queue, retry or device writes occur here. */
    fun encode(limits: NativeNavigationCapabilities.RasterLimits, key: Key, view: View,
               png: ByteArray, verifiedCrc32: Long): Encoded {
        val size = if (view == View.MINI) limits.mini else limits.overview
        val maxBytes = if (view == View.MINI) 65536 else 262144
        val maxFragments = if (view == View.MINI) 16 else 64
        require(limits.fragmentBytes in 1..4096 && size.maxBytes in 1..maxBytes &&
            size.maxFragments in 1..maxFragments)
        require(size.width == (if (view == View.MINI) 184 else 540) &&
            size.height == (if (view == View.MINI) 90 else 220))
        require(verifiedCrc32 in 0..0xffffffffL && png.size in 1..size.maxBytes)
        require(dimensionsMatch(png, size.width, size.height))
        val count = (png.size + limits.fragmentBytes - 1) / limits.fragmentBytes
        require(count in 1..size.maxFragments)
        val fragments = (0 until count).map { index ->
            val offset = index * limits.fragmentBytes
            val bytes = png.copyOfRange(offset, minOf(offset + limits.fragmentBytes, png.size))
            val header = key.json().put("view_mode", view.name).put("codec", "PNG")
                .put("width", size.width).put("height", size.height)
                .put("total_len", png.size).put("fragment_index", index)
                .put("fragment_count", count).put("fragment_offset", offset)
                .put("fragment_len", bytes.size).put("crc32", verifiedCrc32).toString()
            Fragment(header, bytes)
        }
        return Encoded(fragments, key, verifiedCrc32)
    }
}
