// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    for ((age, expected) in listOf(-1L to 0L, 0L to 60000L, 1L to 59999L, 20000L to 40000L, 59999L to 1L, 60000L to 0L, 60001L to 0L, 100000L to 0L)) {
        verify(ProactiveHintPolicy.remainingForSpeech(200000L, 200000L - age) == expected)
    }
    verify(ProactiveHintPolicy.remainingForSpeech(200000, 0) == 0L)
    verify(ProactiveHintPolicy.remainingForSpeech(200000, -1) == 0L)
    for (offset in -2L..2L) verify(ProactiveHintPolicy.remaining(100, 100 + offset) == offset.coerceAtLeast(0))
    val g = ProactiveHintPolicy.DisplayGate()
    verify(!g.accept("", 1, "a", 1, 2))
    verify(!g.accept("r", 0, "a", 1, 2))
    verify(!g.accept("r", 1, "a", 2, 2))
    verify(g.accept("r", 1, "a b", 100, 90000))
    verify(!g.accept("r", 1, "different", 101, 90000))
    verify(!g.accept("r", 2, " a   b ", 29999, 90000))
    verify(g.accept("r", 3, "a b", 30100, 90000))
    verify(!g.accept("r", 2, "different", 30101, 90000))
    verify(!g.accept("r", 4, "[PASS]", 30102, 90000))
    verify(!g.accept("r", 3, "different", 30103, 90000))
    verify(!g.accept("r", 5, "   ", 30104, 90000))
    verify(g.accept("r", 6, "different", 30105, 90000))
    verify(g.accept("next", 1, "different", 30106, 90000))
    verify(!g.accept("next", 2, "different", 30107, 90000))
    val input = ProactiveHintPolicy.InputGate()
    verify(!input.accept("REALTIME", "", "계약 일정", 100))
    verify(!input.accept("REALTIME", "r", " 네! ", 100))
    verify(input.accept("REALTIME", "r", "계약 일정", 100))
    verify(!input.accept("REALTIME", "r", "계약   일정!", 200))
    verify(input.accept("REALTIME", "r", "계약 일정?", 200))
    verify(input.accept("REALTIME", "r", "네?", 200))
    verify(input.accept("REALTIME", "r", "다른 일정", 201))
    verify(input.accept("REALTIME", "r", "계약 일정", 30100))
    verify(input.accept("ALWAYS_ON", "r", "계약 일정", 30101))
    verify(input.accept("ALWAYS_ON", "s", "계약 일정", 30102))
    input.clear()
    verify(input.accept("ALWAYS_ON", "s", "계약 일정", 30103))
    println("$n assertions passed")
}
