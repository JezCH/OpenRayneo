// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, content-free chronological evidence. No raw protocol bytes, text,
 * GPS coordinates, credentials, device IDs, recording data or request IDs. */
internal class DiagnosticTimeline(initial: List<String> = emptyList(), private val limit: Int = 400, private val categoryLimit: Int = 40) {
    private val rows = ArrayDeque(initial.takeLast(limit))
    private val last = mutableMapOf<String, String>()
    fun add(at: Long, category: String, detail: String, snapshot: Boolean = false): Boolean {
        val value = detail.replace('\n', ' ').replace('\r', ' ').take(600)
        if (snapshot && last[category] == value) return false
        if (snapshot) last[category] = value
        rows.addLast("$at | $category | $value")
        // Continuous caption/answer writes must not evict all navigation or
        // LifeLog evidence. Retain recent events independently per category.
        val matching = rows.indices.filter { rows[it].substringAfter(" | ").substringBefore(" | ") == category }
        matching.dropLast(categoryLimit).asReversed().forEach { rows.removeAt(it) }
        while (rows.size > limit) rows.removeFirst()
        return true
    }
    fun entries() = rows.toList()
    fun clear() { rows.clear(); last.clear() }
}

internal class DiagnosticTrace(context: Context) {
    private val prefs = context.getSharedPreferences("diagnostic-metadata-v1", Context.MODE_PRIVATE)
    private val timeline = DiagnosticTimeline(runCatching {
        val saved = JSONArray(prefs.getString("events", "[]"))
        (maxOf(0, saved.length() - 400) until saved.length()).map { saved.getString(it) }
    }.getOrDefault(emptyList()))
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending = false
    private val write = Runnable { pending = false; save() }
    private fun save() { prefs.edit().putString("events", JSONArray(timeline.entries()).toString()).apply() }
    // Coalesce bursts of state changes into one private metadata write.
    fun event(category: String, detail: String, snapshot: Boolean = false) {
        if (timeline.add(System.currentTimeMillis(), category, detail, snapshot) && !pending) {
            pending = true; main.postDelayed(write, 500L)
        }
    }
    fun flush() { main.removeCallbacks(write); pending = false; save() }
    fun export() = timeline.entries().joinToString("\n")
    fun clear() { main.removeCallbacks(write); pending = false; timeline.clear(); prefs.edit().remove("events").apply() }
}

/** Report wire structure rather than raw JSON or arbitrary capability names. */
internal object DiagnosticCapabilityShape {
    private val versionPattern = Regex("""[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}""")
    fun describe(payload: ByteArray): String {
        if (payload.size !in 1..8192) return "size-invalid"
        val body = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return "json-invalid"
        val type = (body.opt("type") as? Int)?.takeIf { it in 1..3 }?.toString() ?: "invalid"
        val ret = when (val value = body.opt("ret")) {
            null -> "absent"
            "OK", "TEMP_UNAVAILABLE", "UNSUPPORTED_TYPE", "INVALID_REQUEST", "INTERNAL_ERROR" -> value.toString()
            else -> "other"
        }
        val list = body.optJSONArray("capabilities")
        if (list == null) return "type=$type ret=$ret entries=absent"
        if (list.length() > 64) return "type=$type ret=$ret entries=oversize"
        var objects = 0
        var pairs = 0
        var other = 0
        val versions = linkedSetOf<String>()
        fun navVersion(value: Any?) {
            versions.add((value as? String)?.takeIf { versionPattern.matches(it) } ?: "invalid")
        }
        for (i in 0 until list.length()) {
            when (val entry = list.opt(i)) {
                is JSONObject -> {
                    val key = entry.keys().let { if (it.hasNext()) it.next() else "" }
                    if (entry.length() == 1 && entry.opt(key) is String) objects++ else other++
                    if (entry.has("app.navigation.main")) navVersion(entry.opt("app.navigation.main"))
                }
                is JSONArray -> {
                    if (entry.length() == 2 && entry.opt(0) is String && entry.opt(1) is String) pairs++ else other++
                    if (entry.opt(0) == "app.navigation.main") navVersion(entry.opt(1))
                }
                else -> other++
            }
        }
        return "type=$type ret=$ret entries=${list.length()} objects=$objects pairs=$pairs other=$other navVersion=${versions.joinToString(",").ifEmpty { "absent" }}"
    }
}
