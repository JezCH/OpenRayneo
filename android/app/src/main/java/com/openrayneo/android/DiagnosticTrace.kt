// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray

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
