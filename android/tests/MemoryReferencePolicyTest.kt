// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    fun source(id: String, text: String, time: Long = 100, record: String = "r", person: String? = "p",
               stable: Boolean = true, mixed: Boolean = false, basis: String = "manual") =
        MemoryReferencePolicy.Source(record, 1, id, time, time + 10, text, person, stable, mixed, basis, "received", false)
    val rows = listOf(source("a", "외주 공사 계약", 100), source("b", "공사 견적 외주", 200),
        source("c", "외주 공사 계약", 300, "s", "q"), source("d", "외주 공사 계약", 400, stable = false),
        source("e", "외주 공사 계약", 500, mixed = true), source("f", "외주 공사 계약", 600, basis = "channel1_default_self"), source("g", "다른 문장"))
    fun find(scope: MemoryReferencePolicy.Scope) = MemoryReferencePolicy.search(scope, rows.asSequence())
    fun ids(scope: MemoryReferencePolicy.Scope) = find(scope).hits.map { it.source.segmentId }
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", limit = 6)) == listOf("f", "e", "d", "c", "a", "b"))
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", limit = 2)) == listOf("f", "e"))
    verify(find(MemoryReferencePolicy.Scope("외주 공사", limit = 2)).more)
    verify(!find(MemoryReferencePolicy.Scope("외주 공사", limit = 6)).more)
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", personId = "p")) == listOf("a", "b"))
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", personId = "q")) == listOf("c"))
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", recordIds = setOf("s"))) == listOf("c"))
    verify(ids(MemoryReferencePolicy.Scope(from = 100, to = 210)) == listOf("b", "a", "g"))
    verify(ids(MemoryReferencePolicy.Scope(from = 101, to = 209)).isEmpty())
    verify(ids(MemoryReferencePolicy.Scope("없는 검색어")).isEmpty())
    verify(ids(MemoryReferencePolicy.Scope("외주 공사", from = 100, to = 410, recordIds = setOf("r"), personId = "p")) == listOf("a", "b"))
    verify(ids(MemoryReferencePolicy.Scope(recordIds = setOf("unknown"))).isEmpty())
    val long = source("long", "앞".repeat(600) + "검색어" + "뒤".repeat(300))
    val hit = MemoryReferencePolicy.search(MemoryReferencePolicy.Scope("검색어"), sequenceOf(long)).hits.single()
    verify(hit.clipped && hit.excerpt.length <= 200 && hit.excerpt.contains("검색어") && long.text.contains(hit.excerpt))
    val emoji = source("emoji", "가".repeat(199) + "😀")
    verify(MemoryReferencePolicy.search(MemoryReferencePolicy.Scope("가"), sequenceOf(emoji)).hits.single().excerpt.length == 199)
    verify(MemoryReferencePolicy.search(MemoryReferencePolicy.Scope("ABC"), sequenceOf(source("latin", "abc"))).hits.size == 1)
    verify(MemoryReferencePolicy.search(MemoryReferencePolicy.Scope("가"), sequenceOf(source("nfc", "가"))).hits.size == 1)
    for (make in listOf<() -> Any>({ MemoryReferencePolicy.Scope() }, { MemoryReferencePolicy.Scope(" ") },
        { MemoryReferencePolicy.Scope("x", limit = 0) }, { MemoryReferencePolicy.Scope("x", limit = 7) },
        { MemoryReferencePolicy.Scope("x".repeat(161)) }, { MemoryReferencePolicy.Scope(from = 0) },
        { MemoryReferencePolicy.Scope(from = 10, to = 10) }, { MemoryReferencePolicy.Scope(from = -1, to = 10) },
        { MemoryReferencePolicy.Scope(recordIds = emptySet()) }, { MemoryReferencePolicy.Scope(personId = "") })) verify(runCatching(make).isFailure)
    println("$n assertions passed")
}
