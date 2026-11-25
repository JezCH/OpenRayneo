// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    for (text in listOf("", " ", "가", "[", "[P", "[PA", "[PASS]", "PASS", "가".repeat(241), "가\uD800")) verify(HintStreamPolicy.preview(text) == null)
    verify(HintStreamPolicy.preview("  안녕하세요  ") == "안녕하세요")
    verify(HintStreamPolicy.preview("가".repeat(240))?.length == 240)
    val gate = HintStreamPolicy.Gate()
    verify(gate.accept("첫 문장", 0) == "첫 문장")
    verify(gate.accept("첫 문장 추가", 199) == null)
    verify(gate.accept("첫 문장 추가", 200) == "첫 문장 추가")
    verify(gate.accept("첫 문장 추가", 1000) == null)
    verify(gate.accept("[PASS]", 1000) == null)
    verify(gate.accept("다른 문장", 1000) == "다른 문장")
    println("$n assertions passed")
}
