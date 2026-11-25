// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.time.Instant
import java.time.ZoneId

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val p = FollowUpPolicy
    val todo = FollowUpPolicy.Candidate("s", FollowUpPolicy.Kind.TODO, "  계약 확인  ")
    val schedule = todo.copy(kind = FollowUpPolicy.Kind.SCHEDULE)
    val id = p.id("r", todo)
    verify(id.startsWith(p.PREFIX) && id.length == p.PREFIX.length + 64)
    verify(id == p.id("r", todo.copy(title = "계약 확인")))
    verify(id != p.id("r2", todo))
    verify(id != p.id("r", schedule))
    verify(id != p.id("r", todo.copy(segmentId = "s2")))
    verify(id != p.id("r", todo.copy(title = "계약 검토")))
    verify(p.validate(listOf(todo, todo.copy(title = "계약 확인")), setOf("s")) == listOf(todo.copy(title = "계약 확인")))
    verify(p.validate(emptyList(), emptySet()).isEmpty())
    verify(runCatching { p.validate(listOf(todo), setOf("other")) }.isFailure)
    verify(runCatching { p.validate(List(6) { todo }, setOf("s")) }.isFailure)
    for (bad in listOf("", " ", "a\nb", "가".repeat(121))) verify(runCatching { p.title(bad) }.isFailure)
    verify(p.title("가".repeat(120)).length == 120)
    val zone = ZoneId.of("Asia/Seoul")
    verify(p.approval(todo.kind, "  업무 ", "", "", zone) == FollowUpPolicy.Approval("업무"))
    verify(runCatching { p.approval(todo.kind, "업무", "2026-11-25 12:00", "", zone) }.isFailure)
    val a = p.approval(schedule.kind, "회의", "2026-11-25 14:00", "2026-11-25 15:00", zone)
    verify(a.start == Instant.parse("2026-11-25T05:00:00Z").epochSecond)
    verify(a.end!! - a.start!! == 3600L)
    for ((start,end) in listOf("" to "", "2026-02-30 10:00" to "2026-03-01 11:00", "2026-11-25 24:00" to "2026-11-26 01:00",
        "2026-11-25 12:00" to "2026-11-25 12:00", "2026-11-25 12:00" to "2026-11-25 11:00", "2100-01-01 10:00" to "2100-01-01 11:00"))
        verify(runCatching { p.approval(schedule.kind, "회의", start, end, zone) }.isFailure)
    for (start in listOf("2026-03-08 02:30", "2026-11-01 01:30"))
        verify(runCatching { p.approval(schedule.kind, "회의", start, start.substringBefore(' ') + " 04:00", ZoneId.of("America/New_York")) }.isFailure)
    for (status in FollowUpPolicy.Status.entries) for (stale in listOf(false,true)) for (source in listOf(false,true))
        verify(p.mayAttempt(status, stale, source) == (status == FollowUpPolicy.Status.PENDING && !stale && source))
    for (readBack in listOf(false,true)) for (event in listOf(null,-1L,0L,80123L))
        verify(p.outcome(readBack,event) == if(readBack && event == 80123L) FollowUpPolicy.Status.CONFIRMED else FollowUpPolicy.Status.UNKNOWN)
    println("$n assertions passed")
}
