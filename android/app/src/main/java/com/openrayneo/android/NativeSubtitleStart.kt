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
            val body = runCatching { JSONObject(message.json ?: return null) }.getOrNull() ?: return null
            val sid = body.opt("sid") as? String ?: return null
            if (!sid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
            if (body.has("code") && body.opt("code") != 0) return null
            if (body.optBoolean("force", false)) return null
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


/** The 0x17 type-1/2 contract is documented by upstream; the exact stock
 * device-originated packet on 1.0.7.11 remains a hardware acceptance test.
 * Accept only the known conversation mode and directions. */
internal data class NativeHintStart(val sid: String, val source: String, val target: String, val direction: String) {
    fun reply() = JSONObject().put("sid", sid).put("code", 1).put("final_settings",
        JSONObject().put("mode", "conversation").put("source_language", source).put("target_language", target)
            .put("direction", direction).put("save_audio", false))
    companion object {
        fun parse(frame: ProtocolCodec.Frame): NativeHintStart? {
            if (frame.protocol != 0x17 || frame.acknowledgement || frame.payload.size !in 1..4096) return null
            val message = ProtocolCodec.message(frame.payload)
            if (message.type != 1 || message.audio?.isNotEmpty() == true) return null
            val body = runCatching { JSONObject(message.json ?: return null) }.getOrNull() ?: return null
            val sid = body.opt("sid") as? String ?: return null
            if (!sid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
            if (body.has("code") && body.opt("code") != 0) return null
            if (body.optBoolean("force", false)) return null
            val settings = body.optJSONObject("settings") ?: return null
            if (settings.opt("mode") != "conversation") return null
            val source = settings.opt("source_language") as? String ?: return null
            val target = settings.opt("target_language") as? String ?: return null
            val direction = settings.opt("direction") as? String ?: return null
            val locale = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")
            if (!source.matches(locale) && source != "auto") return null
            if (!target.matches(locale) && target != "auto") return null
            if (direction !in setOf("ahead", "around")) return null
            return NativeHintStart(sid, source, target, direction)
        }
    }
}
