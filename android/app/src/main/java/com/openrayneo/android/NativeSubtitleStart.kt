// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/** Stock local start waits for type 2. Upstream documents SID/code/final_settings.
 * Exact device-originated behavior on 1.0.7.11 needs hardware verification.
 * No force-start, recording retention, arbitrary language or mode. */
internal data class NativeSubtitleStart(
    val sid: String, val mode: String, val source: String, val target: String, val direction: String
) {
    val captions get() = mode == "classic"
    val languageHint get() = language(source)
    fun reply() = JSONObject().put("sid", sid).put("code", 1).put("final_settings",
        JSONObject().put("mode", mode).put("source_language", source).put("target_language", target)
            .put("direction", direction).put("save_audio", false))
    companion object {
        fun language(value: String): String? = when (value) {
            "ko", "ko-KR" -> "ko"
            "en", "en-US", "en-GB" -> "en"
            "auto" -> "auto"
            else -> null
        }
        fun parse(frame: ProtocolCodec.Frame): NativeSubtitleStart? {
            if (frame.protocol != 0x13 || frame.acknowledgement || frame.payload.size !in 1..4096) return null
            val message = ProtocolCodec.message(frame.payload)
            if (message.type != 1 || message.audio?.isNotEmpty() == true) return null
            val body = JSONObject(message.json ?: return null)
            val sid = body.opt("sid") as? String ?: return null
            if (!sid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
            if (body.has("code") && body.opt("code") != 0) return null
            val settings = body.optJSONObject("settings") ?: return null
            for (key in listOf("mode", "source_language", "target_language", "direction"))
                if (settings.has(key) && settings.opt(key) !is String) return null
            val mode = settings.optString("mode", "classic")
            if (mode !in setOf("classic", "bilingual")) return null
            val source = settings.optString("source_language", "auto")
            val target = settings.optString("target_language", "")
            val direction = settings.optString("direction", "ahead")
            if (direction != "ahead" || language(source) == null) return null
            val request = NativeSubtitleStart(sid, mode, source, target, direction)
            if (!request.captions && (language(source) !in setOf("ko", "en") ||
                language(target) !in setOf("ko", "en") || language(source) == language(target))) return null
            if (request.captions && target.isNotEmpty() && language(target) == null) return null
            return request
        }
    }
}
