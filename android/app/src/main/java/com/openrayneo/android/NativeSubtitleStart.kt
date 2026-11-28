// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/** Only bounded protocol metadata, never SID values, audio or arbitrary JSON. */
private object NativeStartShape {
    private fun kind(value: Any?): String = when (value) {
        null -> "absent"
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is org.json.JSONArray -> "array"
        is String -> "string(${value.length})"
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() in -1000.0..1000.0) value.toString() else "number"
        else -> "other"
    }
    private fun token(value: Any?): String = if (value is String &&
        value.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,31}"))) value
        else kind(value)
    private fun keys(body: JSONObject): String = body.keys().asSequence().take(16)
        .map { if (it.matches(Regex("[A-Za-z_]{1,32}"))) it else "other" }.joinToString(",")
    fun describe(frame: ProtocolCodec.Frame): String {
        if (frame.payload.size !in 1..4096) return "payload=${frame.payload.size}"
        val msg = runCatching { ProtocolCodec.message(frame.payload) }.getOrNull() ?: return "protobuf invalid"
        val body = runCatching { JSONObject(msg.json ?: return "json absent") }.getOrNull() ?: return "json invalid"
        val settings = body.optJSONObject("settings")
        return "v=${msg.version}, type=${msg.type}, mode=${msg.mode}, binary=${msg.audio?.size ?: 0}; " +
            "keys=${keys(body)}; sid=${kind(body.opt("sid"))}, code=${kind(body.opt("code"))}, " +
            "trigger=${kind(body.opt("trigger"))}, force=${kind(body.opt("force"))}; settings=" +
            (settings?.let { "keys=${keys(it)}, mode=${token(it.opt("mode"))}, " +
                "source=${token(it.opt("source_language"))}, target=${token(it.opt("target_language"))}, " +
                "direction=${token(it.opt("direction"))}, save_audio=${kind(it.opt("save_audio"))}" }
                ?: kind(body.opt("settings")))
    }
}
private class NativeStartRejected(val field: String) : IllegalArgumentException(field)
private fun rejectNative(field: String): Nothing = throw NativeStartRejected(field)
internal fun nativeStartRejection(frame: ProtocolCodec.Frame, error: Throwable?): String =
    (if (error is NativeStartRejected) error.field else "envelope decode") + " · " + NativeStartShape.describe(frame)

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
            "zh", "zh-CN", "zh-TW" -> "zh"
            "ja", "ja-JP" -> "ja"
            "auto" -> "auto"
            else -> null
        }
        fun parse(frame: ProtocolCodec.Frame): NativeSubtitleStart? = parseResult(frame).getOrNull()
        fun parseResult(frame: ProtocolCodec.Frame): Result<NativeSubtitleStart> = runCatching {
            decode(frame)
        }
        private fun decode(frame: ProtocolCodec.Frame): NativeSubtitleStart {
            if (frame.protocol != 0x13 || frame.acknowledgement || frame.payload.size !in 1..4096) rejectNative("envelope size/protocol")
            val message = ProtocolCodec.message(frame.payload)
            if (message.type != 1 || message.audio?.isNotEmpty() == true) rejectNative("type/binary")
            val body = runCatching { JSONObject(message.json ?: rejectNative("json absent")) }.getOrNull() ?: rejectNative("json")
            val sid = body.opt("sid") as? String ?: rejectNative("sid type")
            if (!sid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) rejectNative("sid syntax")
            if (body.has("code") && body.opt("code") != 0) rejectNative("code")
            if (body.optBoolean("force", false)) rejectNative("force")
            val settings = body.optJSONObject("settings") ?: rejectNative("settings object")
            for (key in listOf("mode", "source_language", "target_language", "direction"))
                if (settings.has(key) && settings.opt(key) !is String) rejectNative(key + " type")
            val mode = settings.optString("mode", "classic")
            if (mode !in setOf("classic", "bilingual")) rejectNative("subtitle mode")
            val source = settings.optString("source_language", "auto")
            val target = settings.optString("target_language", "")
            val direction = settings.optString("direction", "ahead")
            if (direction != "ahead") rejectNative("subtitle direction")
            if (language(source) == null) rejectNative("subtitle source language")
            val request = NativeSubtitleStart(sid, mode, source, target, direction)
            if (!request.captions && (language(source) !in setOf("ko", "en") ||
                language(target) !in setOf("ko", "en") || language(source) == language(target))) rejectNative("bilingual language pair")
            if (request.captions && target.isNotEmpty() && language(target) == null) rejectNative("subtitle target language")
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
        fun parse(frame: ProtocolCodec.Frame): NativeHintStart? = parseResult(frame).getOrNull()
        fun parseResult(frame: ProtocolCodec.Frame): Result<NativeHintStart> = runCatching {
            decode(frame)
        }
        private fun decode(frame: ProtocolCodec.Frame): NativeHintStart {
            if (frame.protocol != 0x17 || frame.acknowledgement || frame.payload.size !in 1..4096) rejectNative("envelope size/protocol")
            val message = ProtocolCodec.message(frame.payload)
            if (message.type != 1 || message.audio?.isNotEmpty() == true) rejectNative("type/binary")
            val body = runCatching { JSONObject(message.json ?: rejectNative("json absent")) }.getOrNull() ?: rejectNative("json")
            val sid = body.opt("sid") as? String ?: rejectNative("sid type")
            if (!sid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) rejectNative("sid syntax")
            if (body.has("code") && body.opt("code") != 0) rejectNative("code")
            if (body.optBoolean("force", false)) rejectNative("force")
            val settings = body.optJSONObject("settings") ?: rejectNative("settings object")
            if (settings.opt("mode") != "conversation") rejectNative("hint mode")
            val source = settings.opt("source_language") as? String ?: rejectNative("hint source type")
            val target = settings.opt("target_language") as? String ?: rejectNative("hint target type")
            val direction = settings.opt("direction") as? String ?: rejectNative("hint direction type")
            val locale = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")
            if (!source.matches(locale) && source != "auto") rejectNative("hint source language")
            if (!target.matches(locale) && target != "auto") rejectNative("hint target language")
            if (direction !in setOf("ahead", "around")) rejectNative("hint direction")
            return NativeHintStart(sid, source, target, direction)
        }
    }
}
