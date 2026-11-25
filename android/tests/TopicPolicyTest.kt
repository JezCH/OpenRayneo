// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val a = TopicPolicy.create(emptyList(), "t1", "  업무  ")
    verify(a.single().title == "업무")
    val b = TopicPolicy.create(a, "t2", "프로젝트")
    verify(b.size == 2 && a.size == 1)
    val linked = TopicPolicy.link(TopicPolicy.link(b, "t1", "r1", true), "t2", "r1", true)
    verify(linked.all { it.recordIds == setOf("r1") })
    verify(b.all { it.recordIds.isEmpty() })
    verify(TopicPolicy.link(linked, "t1", "r1", true) == linked)
    verify(TopicPolicy.link(linked, "t1", "r1", false).map { it.recordIds } == listOf(emptySet(), setOf("r1")))
    verify(TopicPolicy.remove(linked, "t1").single().recordIds == setOf("r1"))
    verify(TopicPolicy.rename(linked, "t1", "업무2").first().recordIds == setOf("r1"))
    for (bad in listOf("", "  ", "가".repeat(81), "줄\n바꿈", "업무")) verify(runCatching { TopicPolicy.create(a, "t3", bad) }.isFailure)
    verify(runCatching { TopicPolicy.create(a, "t1", "새 주제") }.isFailure)
    verify(runCatching { TopicPolicy.rename(b, "t1", "프로젝트") }.isFailure)
    verify(runCatching { TopicPolicy.rename(b, "missing", "새 주제") }.isFailure)
    verify(runCatching { TopicPolicy.link(b, "missing", "r1", true) }.isFailure)
    verify(runCatching { TopicPolicy.link(b, "t1", "", true) }.isFailure)
    val max = (1..TopicPolicy.MAX_TOPICS).map { TopicPolicy.Topic("t$it", "주제 $it") }
    verify(TopicPolicy.validate(max).size == TopicPolicy.MAX_TOPICS)
    verify(runCatching { TopicPolicy.create(max, "extra", "초과") }.isFailure)
    val full = listOf(TopicPolicy.Topic("x", "주제", (1..TopicPolicy.MAX_RECORDS).map { "r$it" }.toSet()))
    verify(runCatching { TopicPolicy.link(full, "x", "extra", true) }.isFailure)
    verify(TopicPolicy.link(full, "x", "r1", true) == full)
    verify(TopicPolicy.link(full, "x", "r1", false).single().recordIds.size == TopicPolicy.MAX_RECORDS - 1)
    verify(runCatching { TopicPolicy.create(listOf(TopicPolicy.Topic("a", "ABC")), "b", "abc") }.isFailure)
    verify(runCatching { TopicPolicy.create(listOf(TopicPolicy.Topic("a", "가")), "b", "가") }.isFailure)
    println("$n assertions passed")
}
