// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** User-entered Naver destinations stay in encrypted phone storage. Stock firmware
 * receives only opaque slot IDs, never the addresses. Both slots are written only
 * after explicit confirmation: the stock 1.0.6.11 setter replaces both slots.
 * Evidence: parser 0x1075fd00/0x1075fd70, envelope 0x105c4038, store 0x10753334. */
internal object NativeNavigationPresets {
    data class Slot(val id: String, val destination: String) {
        init {
            require(id.matches(Regex("[0-9a-f]{16}")))
            require(validDestination(destination))
        }
        override fun toString() = "Slot(configured=true)"
    }
    data class Config(val revision: Int = 0, val home: Slot? = null, val company: Slot? = null) {
        init { require(revision >= 0 && (home == null || company == null || home.id != company.id)) }
        val complete get() = home != null && company != null
        fun resolve(alias: String) = when(alias) { "집" -> home?.destination; "회사" -> company?.destination; else -> null }
        override fun toString() = "Config(revision=$revision, complete=$complete)"
    }
    private fun validDestination(value: String) = value.length in 2..120 &&
        value.none(Char::isISOControl) && value !in setOf("집", "회사", "여기", "거기", "저기", "어디", "이전 목적지")
    private fun store(context: Context) = AppSettings(context, "native_navigation_presets")
    // Separate opt-in; a saved destination or sync consent is NOT consent to launch.
    private fun handoffSettings(context: Context) = AppSettings(context, "native_preset_handoff")
    fun handoffEnabled(context: Context) = handoffSettings(context).readStored().opt("enabled") == true
    fun saveHandoffEnabled(context: Context, enabled: Boolean) =
        handoffSettings(context).save(JSONObject().put("enabled", enabled))
    fun read(context: Context): Config {
        val json = store(context).readStored()
        if (json.length() == 0) return Config()
        fun slot(key: String): Slot? {
            if (!json.has(key) || json.isNull(key)) return null
            val value = json.getJSONObject(key)
            return Slot(value.getString("id"), value.getString("destination"))
        }
        return Config(json.getInt("revision"), slot("home"), slot("company"))
    }
    fun save(context: Context, home: String, company: String): Config {
        val old = read(context)
        fun update(value: String, previous: Slot?): Slot? {
            val text = value.trim()
            if (text.isEmpty()) return null
            require(validDestination(text)) { "실제 목적지 이름 또는 주소를 입력하세요." }
            if (previous?.destination == text) return previous
            return Slot(UUID.randomUUID().toString().replace("-", "").take(16), text)
        }
        val newHome = update(home, old.home)
        val newCompany = update(company, old.company)
        if (newHome == old.home && newCompany == old.company) return old
        check(old.revision < Int.MAX_VALUE) { "목적지 설정 버전 한도에 도달했습니다." }
        val next = Config(old.revision + 1, newHome, newCompany)
        fun encode(slot: Slot?): Any = slot?.let { JSONObject().put("id", it.id).put("destination", it.destination) } ?: JSONObject.NULL
        store(context).save(JSONObject().put("revision", next.revision).put("home", encode(next.home)).put("company", encode(next.company)))
        return next
    }

    data class Pending(val sequence: Long, val requestId: String, val revision: Int)
    private fun body(message: ProtocolCodec.Message): JSONObject? {
        if (message.version != 1 || message.audio?.isNotEmpty() == true) return null
        val text = message.json ?: return null
        if (text.length !in 1..8192) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }
    fun resultEvidence(message: ProtocolCodec.Message, pending: Pending): String {
        val json = body(message)
        return "type=${message.type} mode=${message.mode} seqMatch=${message.sequence == pending.sequence}" +
            " idPresent=${json?.has("request_id") == true} idMatch=${json?.opt("request_id") == pending.requestId}" +
            " revisionMatch=${json?.opt("revision") == pending.revision} code=${json?.opt("code") as? Int}"
    }
    /** PRESET_SYNC_REPLY is mode=1 and echoes request ID, sequence and revision.
     * Error type 0x40 has mode=2, failed_type and the same positive sequence. */
    fun resultCode(message: ProtocolCodec.Message, pending: Pending): Int? {
        if (message.sequence != pending.sequence) return null
        val json = body(message) ?: return null
        val code = json.opt("code") as? Int ?: return null
        if (message.type == 0x11 && message.mode == 1L &&
            json.opt("request_id") == pending.requestId && json.opt("revision") == pending.revision &&
            (code == 0 || code == 33)) return code
        if (message.type == 0x40 && message.mode == 2L && json.opt("failed_type") == 0x10 &&
            (!json.has("request_id") || json.opt("request_id") == pending.requestId) && code > 0) return code
        return null
    }

    /** Only the recovered initial stock preset request, not other start variants.
     * A matching slot still does not prove that a live Naver route is available. */
    data class InitialRequest(val sid: String, val alias: String) {
        override fun toString() = "InitialRequest(redacted)"
    }
    fun initialPreset(message: ProtocolCodec.Message, config: Config): String? = initialRequest(message, config)?.alias
    fun initialRequest(message: ProtocolCodec.Message, config: Config): InitialRequest? {
        if (message.type != 1 || message.mode != 2L || message.sequence != 0L) return null
        val json = body(message) ?: return null
        val sid = json.opt("sid") as? String ?: return null
        if (!sid.matches(Regex("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"))) return null
        if (json.opt("trigger") != 2 || json.opt("code") != 0) return null
        // Stock initial preset request uses AUTO profiles; other requests are not this handoff.
        if (listOf("engine_profile", "map_profile", "route_scene_profile").any { json.opt(it) != "AUTO" } ||
            json.has("search_id") || json.has("poi_id")) return null
        val alias = when (json.opt("preset")) {
            "HOME" -> "집".takeIf { config.home != null && json.opt("preset_id") == config.home.id }
            "COMPANY" -> "회사".takeIf { config.company != null && json.opt("preset_id") == config.company.id }
            else -> null
        } ?: return null
        return InitialRequest(sid, alias)
    }
}
