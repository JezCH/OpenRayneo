// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Local search over the existing record fields. No AI request, index persistence or data export. */
internal object RecordSearchPolicy {
    fun matches(record: ConversationRecord, people: Map<String, VoicePerson>, query: String): Boolean =
        query.isBlank() || record.title.contains(query, true) || record.summary.contains(query, true) ||
            record.segments.any { it.text.contains(query, true) || it.personId?.let(people::get)?.name?.contains(query, true) == true } ||
            record.memories.any { it.subject.contains(query, true) || it.text.contains(query, true) } ||
            record.summaries.any { it.text.contains(query, true) }

    fun excerpt(record: ConversationRecord, people: Map<String, VoicePerson>, query: String): String {
        fun snippet(text: String): String {
            val index = text.indexOf(query, ignoreCase = true)
            val start = (index - 40).coerceAtLeast(0)
            val end = (start + maxOf(160, query.length + 40)).coerceAtMost(text.length)
            return (if (start > 0) "…" else "") + text.substring(start, end) + if (end < text.length) "…" else ""
        }
        record.segments.firstOrNull { it.text.contains(query, true) }?.let { return "원문 · " + snippet(it.text) }
        record.segments.firstNotNullOfOrNull { segment ->
            val person = segment.personId?.let(people::get)
            if (person != null && person.name.contains(query, true))
                "인물 · " + person.name + " · " + snippet(segment.text) else null
        }?.let { return it }
        record.memories.firstOrNull { it.subject.contains(query, true) || it.text.contains(query, true) }?.let {
            return "기억 · " + snippet(it.subject + " · " + it.text)
        }
        if (record.summary.contains(query, true)) return (if (record.summaryStale) "이전 요약 · " else "요약 · ") + snippet(record.summary)
        record.summaries.firstOrNull { it.text.contains(query, true) }?.let {
            return (if (it.stale) "이전 요약 · " else "요약 · ") + snippet(it.text)
        }
        return "제목 · " + snippet(record.title)
    }
}
