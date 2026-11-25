// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject

/** Reserved, app-owned derived notes reuse encrypted records without changing their source revision. */
internal object FollowUpRecords {
    data class Row(val item: RecordDerived, val candidate: FollowUpPolicy.Candidate, val status: FollowUpPolicy.Status,
                   val approved: FollowUpPolicy.Approval?, val eventId: Long?)
    data class Batch(val segments: List<SpeechSegment>, val payload: JSONObject)
    fun rows(record: ConversationRecord): List<Row> = record.derived.filter { it.id.startsWith(FollowUpPolicy.PREFIX) }.map(::decode)
    fun batch(record: ConversationRecord, page: Int): Batch {
        require(page >= 0 && page.toLong() * FollowUpPolicy.PAGE_SIZE < record.segments.size)
        val segments = record.segments.drop(page * FollowUpPolicy.PAGE_SIZE).take(FollowUpPolicy.PAGE_SIZE)
        val items = JSONArray(segments.map { segment ->
            val end = minOf(400, segment.text.length).let { if (it > 0 && segment.text[it - 1].isHighSurrogate()) it - 1 else it }
            JSONObject().put("segmentId", segment.id).put("text", segment.text.substring(0, end))
                .put("clipped", end < segment.text.length).put("mixed", segment.mixed)
        })
        return Batch(segments, JSONObject().put("segments", items).put("transcriptIncomplete", record.transcriptIncomplete))
    }
    fun parse(answer: String, allowedIds: Set<String>): List<FollowUpPolicy.Candidate> {
        require(answer.length <= 8000) { "후속 조치 응답이 너무 큽니다" }
        val items = JSONObject(answer).getJSONArray("items")
        require(items.length() <= 5)
        val candidates = (0 until items.length()).map { index ->
            val row = items.getJSONObject(index)
            val kind = when (row.opt("kind")) {
                "todo" -> FollowUpPolicy.Kind.TODO
                "schedule" -> FollowUpPolicy.Kind.SCHEDULE
                else -> error("후속 조치 유형이 잘못되었습니다")
            }
            FollowUpPolicy.Candidate(row.opt("segmentId") as? String ?: error("원문 ID가 없습니다"), kind,
                row.opt("title") as? String ?: error("제목이 없습니다"))
        }
        return FollowUpPolicy.validate(candidates, allowedIds)
    }
    private fun encode(row: Row): RecordDerived {
        val body = JSONObject().put("segmentId", row.candidate.segmentId).put("kind", row.candidate.kind.name)
            .put("title", row.candidate.title).put("status", row.status.name)
        row.approved?.let {
            body.put("approvedTitle", it.title)
            if (it.start != null) body.put("start", it.start).put("end", it.end)
        }
        row.eventId?.let { body.put("eventId", it) }
        return row.item.copy(text = body.toString(), agent = "local-followup")
    }
    private fun decode(item: RecordDerived): Row {
        val body = JSONObject(item.text)
        val approval = if (body.has("approvedTitle")) FollowUpPolicy.Approval(body.getString("approvedTitle"),
            if (body.has("start")) body.getLong("start") else null, if (body.has("end")) body.getLong("end") else null) else null
        return Row(item, FollowUpPolicy.Candidate(body.getString("segmentId"), FollowUpPolicy.Kind.valueOf(body.getString("kind")),
            body.getString("title")), FollowUpPolicy.Status.valueOf(body.getString("status")), approval,
            if (body.has("eventId")) body.getLong("eventId") else null)
    }
    fun propose(repository: ConversationRepository, recordId: String, sourceRevision: Int, page: Int, answer: String): Int {
        var added = 0
        repository.mutateFollowUps(recordId, sourceRevision) { record ->
            val old = rows(record).associateBy { it.item.id }.toMutableMap()
            val candidates = parse(answer, batch(record, page).segments.map { it.id }.toSet())
            for (candidate in candidates) {
                val id = FollowUpPolicy.id(recordId, candidate)
                val prior = old[id]
                if (prior != null && (prior.status != FollowUpPolicy.Status.PENDING || !prior.item.stale)) continue
                require(prior != null || old.size < FollowUpPolicy.MAX_ITEMS) { "기록당 후속 조치 후보는 최대 40개입니다" }
                val item = RecordDerived(id = id, recordId = recordId, sourceRevision = record.revision,
                    revision = (prior?.item?.revision ?: 0) + 1, kind = "note", agent = "local-followup")
                old[id] = Row(item, candidate, FollowUpPolicy.Status.PENDING, null, null)
                added++
            }
            old.values.map(::encode)
        }
        return added
    }
    fun reserve(repository: ConversationRepository, recordId: String, sourceRevision: Int, id: String,
                itemRevision: Int, approval: FollowUpPolicy.Approval): Row {
        var reserved: Row? = null
        repository.mutateFollowUps(recordId, sourceRevision) { record ->
            rows(record).map { row ->
                if (row.item.id != id) return@map encode(row)
                check(row.item.revision == itemRevision && FollowUpPolicy.mayAttempt(row.status,
                    row.item.stale || row.item.sourceRevision != record.revision,
                    record.segments.any { it.id == row.candidate.segmentId })) { "후속 조치가 변경되었거나 이미 처리되었습니다" }
                val clean = FollowUpPolicy.title(approval.title)
                require(if (row.candidate.kind == FollowUpPolicy.Kind.TODO) approval.start == null && approval.end == null
                    else approval.start != null && approval.end != null && approval.end > approval.start)
                val next = row.copy(item = row.item.copy(revision = row.item.revision + 1, updatedAt = System.currentTimeMillis()),
                    status = FollowUpPolicy.Status.ATTEMPTED, approved = approval.copy(title = clean))
                reserved = next
                encode(next)
            }.also { check(reserved != null) { "후속 조치가 없습니다" } }
        }
        return checkNotNull(reserved)
    }
    fun finish(repository: ConversationRepository, recordId: String, id: String, itemRevision: Int, result: JSONObject?): FollowUpPolicy.Status {
        val event = result?.optLong("eventID", -1L)?.takeIf { it > 0 }
        val status = FollowUpPolicy.outcome(result?.optBoolean("readBack") == true, event)
        repository.mutateFollowUps(recordId, null) { record ->
            var found = false
            rows(record).map { row ->
                if (row.item.id != id) return@map encode(row)
                check(row.item.revision == itemRevision && row.status == FollowUpPolicy.Status.ATTEMPTED)
                found = true
                encode(row.copy(item = row.item.copy(revision = row.item.revision + 1, updatedAt = System.currentTimeMillis()),
                    status = status, eventId = event))
            }.also { check(found) }
        }
        return status
    }
    fun dismiss(repository: ConversationRepository, recordId: String, id: String, itemRevision: Int) {
        repository.mutateFollowUps(recordId, null) { record ->
            var found = false
            rows(record).map { row ->
                if (row.item.id != id) return@map encode(row)
                check(row.item.revision == itemRevision && row.status == FollowUpPolicy.Status.PENDING)
                found = true
                encode(row.copy(item = row.item.copy(revision = row.item.revision + 1, updatedAt = System.currentTimeMillis()),
                    status = FollowUpPolicy.Status.DISMISSED))
            }.also { check(found) }
        }
    }
}
