// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Candidate text is untrusted; only a reviewed, explicit UI action may reserve a device write. */
internal object FollowUpPolicy {
    const val PREFIX = "followup:"
    const val MAX_ITEMS = 40
    const val PAGE_SIZE = 20
    const val MAX_TITLE = 120
    enum class Kind { TODO, SCHEDULE }
    enum class Status { PENDING, ATTEMPTED, CONFIRMED, UNKNOWN, DISMISSED }
    data class Candidate(val segmentId: String, val kind: Kind, val title: String)
    data class Approval(val title: String, val start: Long? = null, val end: Long? = null)
    fun title(value: String): String = value.trim().also {
        require(it.length in 1..MAX_TITLE && it.none(Char::isISOControl)) { "제목은 1~120자여야 합니다" }
    }
    fun id(recordId: String, candidate: Candidate): String {
        val values = listOf(recordId, candidate.segmentId, candidate.kind.name, title(candidate.title))
        val raw = values.joinToString("") { "${it.length}:$it" }
        return PREFIX + MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun validate(candidates: List<Candidate>, allowedIds: Set<String>): List<Candidate> {
        require(candidates.size <= 5) { "구간당 후보는 최대 5개입니다" }
        return candidates.map {
            require(it.segmentId in allowedIds) { "요청한 원문 구간에 없는 출처입니다" }
            it.copy(title = title(it.title))
        }.distinct()
    }
    fun approval(kind: Kind, title: String, start: String, end: String, zone: ZoneId): Approval {
        val name = title(title)
        if (kind == Kind.TODO) {
            require(start.isBlank() && end.isBlank())
            return Approval(name)
        }
        val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
        fun seconds(value: String): Long {
            val local = LocalDateTime.parse(value.trim(), formatter)
            require(local.year in 2000..2099) { "일정 연도는 2000~2099년이어야 합니다" }
            val offsets = zone.rules.getValidOffsets(local)
            require(offsets.size == 1) { "시간대에서 존재하지 않거나 모호한 시각입니다" }
            return local.toEpochSecond(offsets.single())
        }
        val from = seconds(start); val to = seconds(end)
        require(to > from) { "종료 시각은 시작 이후여야 합니다" }
        return Approval(name, from, to)
    }
    fun mayAttempt(status: Status, stale: Boolean, sourceExists: Boolean) =
        status == Status.PENDING && !stale && sourceExists
    fun outcome(readBack: Boolean, eventId: Long?) =
        if (readBack && eventId != null && eventId > 0) Status.CONFIRMED else Status.UNKNOWN
}
