// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/** Stock 1.0.4.12 / 1.0.6.11 PbMsg descriptors and the 0x30 capability
 * handler. The actual response type and complete request JSON remain
 * unverified on the user's firmware. Do not derive a session ACK from a
 * matching transport sequence or a successful socket write. */
internal data class NativeNavigationCapabilities(
    val phoneGuidance: Boolean, val amap: Boolean, val guidance: Boolean, val maps: Boolean
) {
    val summary: String get() =
        "PHONE_GUIDANCE_V1 " + (if (phoneGuidance) "지원 보고" else "미보고") +
            " · AMAP_PB_V1 " + (if (amap) "지원 보고" else "미보고") +
            " · 길 안내 " + (if (guidance) "지원" else "미지원") +
            " · 지도 " + (if (maps) "지원" else "미지원")

    companion object {
        fun parse(message: ProtocolCodec.Message): NativeNavigationCapabilities? {
            if (message.version != 1 || message.type in setOf(1, 2, 3, 0x30) ||
                message.type < 0 || message.audio?.isNotEmpty() == true) return null
            val text = message.json ?: return null
            if (text.length !in 1..8192) return null
            val body = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (body.opt("protocol_version") != 1) return null
            val engines = body.optJSONArray("supported_engine_profiles") ?: return null
            if (engines.length() > 32 || (0 until engines.length()).any { engines.opt(it) !is String }) return null
            val guidance = body.opt("guidance_supported") as? Boolean ?: return null
            val maps = body.opt("map_supported") as? Boolean ?: return null
            val profiles = (0 until engines.length()).map { engines.optString(it) }.toSet()
            return NativeNavigationCapabilities("PHONE_GUIDANCE_V1" in profiles, "AMAP_PB_V1" in profiles, guidance, maps)
        }
    }
}
