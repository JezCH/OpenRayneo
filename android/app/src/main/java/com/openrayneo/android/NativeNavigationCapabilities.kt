// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/** Stock 1.0.4.12 / 1.0.6.11 PbMsg descriptors and the 0x30 capability
 * handler and the official app's 0x31 GLASS_CAPABILITY enum (AOT
 * 0x1c52d08/0x53448). Stock 1.0.6.11 parser 0x10760e8c requires
 * request_id and required_protocol_version; handler 0x105c9750 echoes
 * request_id and reports code 0 or 17. Live acceptance on newer firmware
 * remains unverified. Never treat a capability response as a session ACK. */
internal data class NativeNavigationCapabilities(
    val phoneGuidance: Boolean, val amap: Boolean, val guidance: Boolean, val maps: Boolean,
    val raster: RasterLimits? = null
) {
    val summary: String get() =
        "PHONE_GUIDANCE_V1 " + (if (phoneGuidance) "지원 보고" else "미보고") +
            " · AMAP_PB_V1 " + (if (amap) "지원 보고" else "미보고") +
            " · 길 안내 " + (if (guidance) "지원" else "미지원") +
            " · 지도 " + (if (maps) "지원" else "미지원") +
            (raster?.let { " · PNG 프레임 ${it.mini.width}×${it.mini.height}/${it.overview.width}×${it.overview.height} · 조각 ${it.fragmentBytes}B" }
                ?: " · 원격 PNG 세부 규격 미확인")

    internal data class ViewLimits(val width: Int, val height: Int, val maxBytes: Int, val maxFragments: Int)
    internal data class RasterLimits(val fragmentBytes: Int, val mini: ViewLimits, val overview: ViewLimits)
    
    companion object {
        // Retain only the positively reported intersection with the recovered
        // 1.0.6.11 raster contract; missing or incompatible fields are not defaults.
        private fun rasterLimits(body: JSONObject): RasterLimits? {
            val maps = body.optJSONArray("supported_map_profiles") ?: return null
            if (maps.length() !in 1..32 || (0 until maps.length()).any { maps.opt(it) !is String } ||
                (0 until maps.length()).none { maps.optString(it) == "REMOTE_RASTER_FRAME_V1" }) return null
            if (body.opt("map_supported") != true || body.opt("map_frame_codec") != "PNG" ||
                body.opt("single_frame_in_flight") != true) return null
            val fragment = body.opt("map_frame_fragment_size") as? Int ?: return null
            // Stock assembler uses fixed 4096-byte slots, not arbitrary smaller chunks.
            if (fragment != 4096) return null
            fun view(prefix: String, w: Int, h: Int, max: Int, count: Int): ViewLimits? {
                if (body.opt("${prefix}_map_width") != w || body.opt("${prefix}_map_height") != h) return null
                val bytes = body.opt("${prefix}_max_png_bytes") as? Int ?: return null
                val parts = body.opt("${prefix}_max_fragment_count") as? Int ?: return null
                if (bytes !in 1..max || parts !in 1..count) return null
                return ViewLimits(w, h, minOf(bytes, parts * fragment), parts)
            }
            return RasterLimits(fragment, view("mini", 184, 90, 65536, 16) ?: return null,
                view("overview", 540, 220, 262144, 64) ?: return null)
        }

        fun correlatedReply(message: ProtocolCodec.Message, requestId: String): JSONObject? {
            // Actual v1449 / stock peer trace: 0x31 mode=2 version=1 code=0.
            if (message.version != 1 || message.type != 0x31 || message.mode !in setOf(1L, 2L)) return null
            val body = readBody(message) ?: return null
            return body.takeIf { it.opt("request_id") == requestId }
        }

        /** Mode 2 is the observed peer response envelope. The unique echoed
         * request ID remains mandatory, even if firmware does not echo PbMsg.seq.
         * Old mode 1 and uncorrelated error messages keep the sequence guard. */
        fun matchesPending(message: ProtocolCodec.Message, requestId: String, sequence: Long): Boolean =
            if (message.type == 0x31 && message.mode == 2L) correlatedReply(message, requestId) != null
            else message.sequence == sequence

        fun replyEvidence(message: ProtocolCodec.Message, requestId: String, sequence: Long): String {
            val body = readBody(message)
            val match = body?.opt("request_id") == requestId
            val engineCount = body?.optJSONArray("supported_engine_profiles")?.length() ?: -1
            val mapCount = body?.optJSONArray("supported_map_profiles")?.length() ?: -1
            return "type=${message.type} mode=${message.mode} version=${message.version} seqMatch=${message.sequence == sequence} idPresent=${body?.has("request_id") == true} idMatch=$match engineCount=$engineCount mapCount=$mapCount guidance=${body?.opt("guidance_supported") == true} maps=${body?.opt("map_supported") == true}"
        }

        // Only protocol constants and numeric/boolean field shape; no raw
        // body, request identifiers, unknown profile strings or destinations.
        fun schemaEvidence(message: ProtocolCodec.Message): String {
            val body = readBody(message) ?: return "body unavailable or invalid"
            fun flag(key: String) = (body.opt(key) as? Boolean)?.toString() ?: "missing/invalid"
            fun integer(key: String) = (body.opt(key) as? Int)?.toString() ?: "missing/invalid"
            fun profiles(key: String, allowed: Set<String>): String {
                val values = body.optJSONArray(key) ?: return "missing/invalid"
                val known = allowed.filter { name -> (0 until values.length()).any { values.opt(it) == name } }
                return known.joinToString(",").ifBlank { "none" } + " (total=${values.length()})"
            }
            return "engines=" + profiles("supported_engine_profiles", setOf("PHONE_GUIDANCE_V1","AMAP_PB_V1")) +
                " maps=" + profiles("supported_map_profiles", setOf("REMOTE_RASTER_FRAME_V1","REMOTE_VECTOR_TILE_V1","NONE")) +
                " guidance=" + flag("guidance_supported") + " map=" + flag("map_supported") +
                " png=" + (body.opt("map_frame_codec") == "PNG") + " single=" + flag("single_frame_in_flight") +
                " frag=" + integer("map_frame_fragment_size") +
                listOf("mini","overview").joinToString("") { prefix ->
                    " $prefix=" + listOf("map_width","map_height","max_png_bytes","max_fragment_count").joinToString("/") { integer("${prefix}_$it") }
                }
        }

        fun queryError(message: ProtocolCodec.Message, requestId: String): Int? {
            if (message.version != 1 || message.type != 0x40 || message.mode != 2L) return null
            val body = readBody(message) ?: return null
            if (body.opt("failed_type") != 0x30) return null
            if (body.has("request_id") && body.opt("request_id") != requestId) return null
            return (body.opt("code") as? Int)?.takeIf { it > 0 }
        }

        private fun readBody(message: ProtocolCodec.Message): JSONObject? {
            if (message.audio?.isNotEmpty() == true) return null
            val text = message.json ?: return null
            if (text.length !in 1..8192) return null
            return runCatching { JSONObject(text) }.getOrNull()
        }

        fun parse(message: ProtocolCodec.Message, requestId: String): NativeNavigationCapabilities? {
            val body = correlatedReply(message, requestId) ?: return null
            if (body.opt("code") != 0 || body.opt("protocol_version") != 1) return null
            val engines = body.optJSONArray("supported_engine_profiles") ?: return null
            if (engines.length() > 32 || (0 until engines.length()).any { engines.opt(it) !is String }) return null
            val guidance = body.opt("guidance_supported") as? Boolean ?: return null
            val maps = body.opt("map_supported") as? Boolean ?: return null
            val profiles = (0 until engines.length()).map { engines.optString(it) }.toSet()
            return NativeNavigationCapabilities("PHONE_GUIDANCE_V1" in profiles, "AMAP_PB_V1" in profiles, guidance, maps,
                rasterLimits(body).takeIf { "PHONE_GUIDANCE_V1" in profiles && guidance && maps })
        }
    }
}
