// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.text.Normalizer
import java.util.Locale
import java.util.PriorityQueue

/** Local lexical reference selection; no inference, network or mutation. */
internal object MemoryReferencePolicy {
    const val MAX_RESULTS = 6
    const val MAX_TEXT = 200
    data class Scope(val query: String = "", val from: Long? = null, val to: Long? = null,
                     val recordIds: Set<String>? = null, val personId: String? = null, val limit: Int = 4) {
        init {
            require(query.length <= 160 && limit in 1..MAX_RESULTS)
            require((from == null) == (to == null) && (from == null || (from >= 0 && to!! > from)))
            require(recordIds == null || (recordIds.size in 1..20 && recordIds.all { it.isNotBlank() && it.length <= 128 }))
            require(personId == null || (personId.isNotBlank() && personId.length <= 128))
            require(query.isNotBlank() || from != null || recordIds != null || personId != null) { "검색어 또는 명시적인 참조 범위가 필요합니다" }
        }
    }
    data class Source(val recordId: String, val recordRevision: Int, val segmentId: String, val start: Long,
                      val end: Long, val text: String, val personId: String?, val stable: Boolean,
                      val mixed: Boolean, val identityBasis: String, val timeBasis: String, val incomplete: Boolean)
    data class Hit(val source: Source, val excerpt: String, val clipped: Boolean, val score: Int)
    data class Result(val hits: List<Hit>, val more: Boolean)
    private fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private val order = compareByDescending<Hit> { it.score }.thenByDescending { it.source.start }
        .thenBy { it.source.recordId }.thenBy { it.source.segmentId }

    fun search(scope: Scope, sources: Sequence<Source>): Result {
        val query = normalize(scope.query)
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        val best = PriorityQueue<Hit>(scope.limit, order.reversed())
        var count = 0L
        for (source in sources) {
            if (source.recordId.isBlank() || source.segmentId.isBlank() || source.text.isBlank()) continue
            if (scope.recordIds != null && source.recordId !in scope.recordIds) continue
            if (scope.from != null && (source.start < scope.from || source.end > scope.to!! || source.end <= source.start)) continue
            if (scope.personId != null && (source.personId != scope.personId || !source.stable || source.mixed || source.identityBasis in setOf("unknown", "mixed", "channel1_default_self"))) continue
            val text = normalize(source.text)
            val score = when {
                query.isEmpty() -> 0
                text.contains(query) -> 2
                tokens.size > 1 && tokens.all { text.contains(it) } -> 1
                else -> continue
            }
            // Keep an original-text excerpt, not normalized or generated wording.
            val raw = source.text
            val index = if (query.isEmpty()) 0 else raw.indexOf(scope.query.trim(), ignoreCase = true).takeIf { it >= 0 }
                ?: tokens.firstNotNullOfOrNull { token -> raw.indexOf(token, ignoreCase = true).takeIf { it >= 0 } } ?: 0
            val start = (index - 32).coerceAtLeast(0).let { if (it > 0 && raw[it].isLowSurrogate()) it - 1 else it }
            val end = (start + MAX_TEXT).coerceAtMost(raw.length).let { if (it > start && raw[it - 1].isHighSurrogate()) it - 1 else it }
            val hit = Hit(source, raw.substring(start, end), start > 0 || end < raw.length, score)
            count++
            if (best.size < scope.limit) best.add(hit)
            else if (order.compare(hit, best.peek()) < 0) { best.poll(); best.add(hit) }
        }
        return Result(best.toList().sortedWith(order), count > scope.limit)
    }
}
