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
    val phoneGuidance: Boolean, val amap: Boolean, val guidance: Boolean, val maps: Boolean
) {
    val summary: String get() =
        "PHONE_GUIDANCE_V1 " + (if (phoneGuidance) "지원 보고" else "미보고") +
            " · AMAP_PB_V1 " + (if (amap) "지원 보고" else "미보고") +
            " · 길 안내 " + (if (guidance) "지원" else "미지원") +
            " · 지도 " + (if (maps) "지원" else "미지원")

    companion object {
        fun correlatedReply(message: ProtocolCodec.Message, requestId: String): JSONObject? {
            if (message.version != 1 || message.type != 0x31 || message.mode != 1L) return null
            val body = readBody(message) ?: return null
            return body.takeIf { it.opt("request_id") == requestId }
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
            return NativeNavigationCapabilities("PHONE_GUIDANCE_V1" in profiles, "AMAP_PB_V1" in profiles, guidance, maps)
        }
    }
}
