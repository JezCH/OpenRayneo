// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject

/** Adapter from the encrypted repository's immutable record snapshot to source-only references. */
internal object MemoryReferenceSearch {
    private fun string(args: JSONObject, key: String): String? =
        if (args.has(key)) args.opt(key) as? String ?: error("문자열 인수가 필요합니다: $key") else null
    private fun integer(args: JSONObject, key: String, max: Long): Long? {
        if (!args.has(key)) return null
        val raw = args.opt(key) as? Number ?: error("정수 인수가 필요합니다: $key")
        val value = raw.toLong()
        require(value in 0..max && raw.toDouble() == value.toDouble()) { "정수 범위가 잘못되었습니다: $key" }
        return value
    }
    fun scope(args: JSONObject): MemoryReferencePolicy.Scope {
        val ids = if (args.has("recordIds")) {
            val array = args.optJSONArray("recordIds") ?: error("기록 ID 배열이 필요합니다")
            require(array.length() in 1..20)
            (0 until array.length()).map { array.opt(it) as? String ?: error("기록 ID는 문자열이어야 합니다") }.toSet()
        } else null
        return MemoryReferencePolicy.Scope(string(args, "query").orEmpty(), integer(args, "from", 253402300799999L),
            integer(args, "to", 253402300799999L), ids, string(args, "personId"),
            integer(args, "limit", MemoryReferencePolicy.MAX_RESULTS.toLong())?.toInt() ?: 4)
    }
    fun search(scope: MemoryReferencePolicy.Scope, records: List<ConversationRecord>): MemoryReferencePolicy.Result {
        val sources = records.asSequence().filter { scope.recordIds == null || it.id in scope.recordIds }
            .flatMap { row -> row.segments.asSequence().map { segment ->
                MemoryReferencePolicy.Source(row.id, row.revision, segment.id, row.timeOf(segment), row.endTimeOf(segment),
                    segment.text, segment.personId, segment.stable, segment.mixed, segment.identityBasis, segment.timeBasis, row.transcriptIncomplete)
            } }
        return MemoryReferencePolicy.search(scope, sources)
    }
    fun related(query: String, records: List<ConversationRecord>): MemoryReferencePolicy.Result {
        require(query.isNotBlank() && query.length <= 160)
        val sources = records.asSequence().filter { it.mode != ConversationMode.ENROLL }
            .flatMap { row -> row.segments.asSequence().map { segment ->
                MemoryReferencePolicy.Source(row.id, row.revision, segment.id, row.timeOf(segment), row.endTimeOf(segment),
                    segment.text, segment.personId, segment.stable, segment.mixed, segment.identityBasis, segment.timeBasis, row.transcriptIncomplete)
            } }
        return AutomaticMemoryPolicy.related(query, MemoryReferencePolicy.MAX_RESULTS, sources)
    }
    fun automatic(question: String, options: AutomaticMemoryPolicy.Options, now: Long, records: List<ConversationRecord>): MemoryReferencePolicy.Result {
        val sources = records.asSequence().filter { it.endedAt != null && !it.processingPending && !it.audioPending && it.mode != ConversationMode.ENROLL }
            .flatMap { row -> row.segments.asSequence().map { segment ->
                MemoryReferencePolicy.Source(row.id, row.revision, segment.id, row.timeOf(segment), row.endTimeOf(segment),
                    segment.text, segment.personId, segment.stable, segment.mixed, segment.identityBasis, segment.timeBasis, row.transcriptIncomplete)
            } }
        return AutomaticMemoryPolicy.search(question, options, now, sources)
    }
    fun current(result: MemoryReferencePolicy.Result, records: List<ConversationRecord>): Boolean =
        result.hits.all { hit -> records.any { row -> row.id == hit.source.recordId && row.revision == hit.source.recordRevision &&
            row.segments.any { segment -> segment.id == hit.source.segmentId && segment.text == hit.source.text &&
                segment.text.contains(hit.excerpt) } } }

    /** Retain resolvable original IDs and distinguish a transcription from an established fact. */
    fun json(result: MemoryReferencePolicy.Result): JSONObject {
        val items = JSONArray()
        var more = result.more
        for (hit in result.hits) {
            val source = hit.source
            val row = JSONObject().put("recordId", source.recordId).put("recordRevision", source.recordRevision)
                .put("segmentId", source.segmentId).put("from", source.start).put("to", source.end)
                .put("text", hit.excerpt).put("clipped", hit.clipped).put("timeBasis", source.timeBasis)
                .put("identityBasis", source.identityBasis).put("stable", source.stable).put("mixed", source.mixed)
                .put("transcriptIncomplete", source.incomplete)
            if (source.personId != null && source.stable && !source.mixed &&
                source.identityBasis !in setOf("unknown", "mixed", "channel1_default_self")) row.put("personId", source.personId)
            // The DSH adapter bounds tool results to 8 KiB. Leave room for outer fields.
            if (items.toString().toByteArray(Charsets.UTF_8).size + row.toString().toByteArray(Charsets.UTF_8).size > 6800) {
                more = true
                break
            }
            items.put(row)
        }
        return JSONObject().put("items", items).put("more", more).put("sourceType", "transcript")
            .put("notice", "원문 전사 검색 결과입니다. 발화가 사실임을 보장하지 않으며 화자와 시각은 추정일 수 있습니다. more=true이면 검색 범위를 좁히세요.")
    }
}
