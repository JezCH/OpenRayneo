// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray

/** Bounded, content-free chronological evidence. No raw protocol bytes, text,
 * GPS coordinates, credentials, device IDs, recording data or request IDs. */
internal class DiagnosticTimeline(initial: List<String> = emptyList(), private val limit: Int = 300) {
    private val rows = ArrayDeque(initial.takeLast(limit))
    private val last = mutableMapOf<String, String>()
    fun add(at: Long, category: String, detail: String, snapshot: Boolean = false): Boolean {
        val value = detail.replace('\n', ' ').replace('\r', ' ').take(600)
        if (snapshot && last[category] == value) return false
        if (snapshot) last[category] = value
        rows.addLast("$at | $category | $value")
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
        (0 until saved.length()).takeLast(300).map { saved.getString(it) }
    }.getOrDefault(emptyList()))
    // Call from main. apply() writes asynchronously; no disk wait on audio or BLE.
    fun event(category: String, detail: String, snapshot: Boolean = false) {
        if (timeline.add(System.currentTimeMillis(), category, detail, snapshot))
            prefs.edit().putString("events", JSONArray(timeline.entries()).toString()).apply()
    }
    fun export() = timeline.entries().joinToString("\n")
    fun clear() { timeline.clear(); prefs.edit().remove("events").apply() }
}
