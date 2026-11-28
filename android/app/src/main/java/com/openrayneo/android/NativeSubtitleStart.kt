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
                "src_lang=${token(it.opt("src_lang"))}, dst_lang=${token(it.opt("dst_lang"))}, " +
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

    /** Classic caption settings are negotiated with the actual phone ASR,
     * not the glass's old default locale. Bilingual requests retain their
     * explicitly selected pair; firmware label rendering needs device proof. */
    fun withPhoneLanguage(config: JSONObject): NativeSubtitleStart =
        if (captions) copy(source = NativeSpeechLanguage.source(config), target = "") else this

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
            // Stock 1.0.6.11 startTranslation passes its successful startup
            // result (1) to sendStartRequest (0x106fd1ce -> 0x10703478).
            // Phone-originated examples use 0; keep both known success/request
            // forms, but never admit a non-success startup error or force.
            if (body.has("code") && body.opt("code") !in setOf(0, 1)) rejectNative("code")
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
internal data class NativeHintStart(val sid: String, val source: String, val target: String, val direction: String,
                                    val stockLanguageKeys: Boolean = false, val mode: String = "conversation") {
    /** Realtime hint capture uses the configured phone ASR. Its answer is
     * generated in the phone's answer language, not a stale peer default. */
    fun withPhoneLanguage(config: JSONObject): NativeHintStart =
        copy(source = NativeSpeechLanguage.source(config),
            target = if (mode == "classic" && target.isEmpty()) "" else KoreanLanguage.answerLanguage(config.optString("answerLanguage")))

    fun reply() = JSONObject().put("sid", sid).put("code", 1).put("final_settings",
        JSONObject().put("mode", mode)
            .put(if (stockLanguageKeys) "src_lang" else "source_language", source)
            .put(if (stockLanguageKeys) "dst_lang" else "target_language", target)
            .put("direction", direction).put("save_audio", false))
    companion object {
        private fun languageField(settings: JSONObject, stock: String, legacy: String): String {
            val primary = if (settings.has(stock)) settings.opt(stock) as? String ?: rejectNative("$stock type") else null
            val alternate = if (settings.has(legacy)) settings.opt(legacy) as? String ?: rejectNative("$legacy type") else null
            if (!primary.isNullOrEmpty() && !alternate.isNullOrEmpty() && primary != alternate)
                rejectNative("$stock conflicting alias")
            return primary?.takeIf { it.isNotEmpty() } ?: alternate ?: primary ?: rejectNative("$stock missing")
        }
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
            // Stock request decoder 0x105a1768 defaults code to 1; retain the
            // existing phone-originated 0 form and reject other status values.
            if (body.has("code") && body.opt("code") !in setOf(0, 1)) rejectNative("code")
            if (body.optBoolean("force", false)) rejectNative("force")
            val settings = body.optJSONObject("settings") ?: rejectNative("settings object")
            // Stock local start constructor 0x105a0b7c initializes trigger=2,
            // code=1, mode=classic, src_lang=auto, dst_lang="", direction=ahead.
            // This is ConversationAssist (0x17), not the separate 0x13 caption.
            val mode = settings.opt("mode") as? String ?: rejectNative("hint mode type")
            if (mode !in setOf("classic", "conversation")) rejectNative("hint mode")
            // Stock serializer 0x105a1d14 emits src_lang/dst_lang. The same
            // settings decoder (0x105a1600, used for start and final_settings)
            // reads these first and falls back to source/target_language.
            val stockKeys = settings.has("src_lang") || settings.has("dst_lang")
            val source = languageField(settings, "src_lang", "source_language")
            val target = languageField(settings, "dst_lang", "target_language")
            val direction = settings.opt("direction") as? String ?: rejectNative("hint direction type")
            val locale = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")
            if (!source.matches(locale) && source != "auto") rejectNative("hint source language")
            if (!target.matches(locale) && target != "auto" && !(mode == "classic" && target.isEmpty())) rejectNative("hint target language")
            if (direction !in setOf("ahead", "around")) rejectNative("hint direction")
            return NativeHintStart(sid, source, target, direction, stockKeys, mode)
        }
    }
}

/** Local speech/answer preferences, not a write to firmware language settings.
 * Stock 1.0.6.11 parser 0x1070DEEC copies these settings strings; the
 * existing parser accepts the corresponding locale tags. Actual label
 * rendering on 1.0.7.11 must be observed, not assumed. */
internal object NativeSpeechLanguage {
    fun source(config: JSONObject): String {
        if (SpeechRounds.engine(config) == "stream") return "ko-KR"
        val hints = SpeechRounds.languageHints(config).split(',').map { it.trim() }
            .filter { it.isNotEmpty() }.distinct()
        return when (hints.singleOrNull()) {
            "ko", "ko-KR" -> "ko-KR"
            "en", "en-US" -> "en-US"
            "zh", "zh-CN" -> "zh-CN"
            "ja", "ja-JP" -> "ja-JP"
            else -> "auto"
        }
    }
}
