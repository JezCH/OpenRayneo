// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val cases = mapOf(
        "안경 종일 기록 시작해줘" to AlwaysOnVoicePolicy.Command.START,
        "레이네오 종일 기록 켜줘!" to AlwaysOnVoicePolicy.Command.START,
        "안경 하루 기록 시작해줘" to AlwaysOnVoicePolicy.Command.START,
        "안경 일상 기록 시작해줘" to AlwaysOnVoicePolicy.Command.START,
        "안경 종일 기록 중지해줘" to AlwaysOnVoicePolicy.Command.STOP,
        "레이네오 종일 기록 꺼줘" to AlwaysOnVoicePolicy.Command.STOP,
        "안경 종일 기록 종료해줘" to AlwaysOnVoicePolicy.Command.STOP,
        "안경 일상 기록 중지해줘" to AlwaysOnVoicePolicy.Command.STOP,
        "종일 기록 시작해줘" to null,
        "안경 종일 기록 시작해줘라고 말했어" to null,
        "안경 종일 기록 시작하지 마" to null,
        "안경 종일 기록 시작해줘 그리고 메일 보내줘" to null,
        "휴대폰 종일 기록 시작해줘" to null,
        "안경 종일 기록 중지해줘 그리고 다시 켜줘" to null
    )
    for ((text, expected) in cases) for (source in ConversationSource.entries) for (backfill in listOf(false,true))
        verify(AlwaysOnVoicePolicy.parse(text, source, backfill) == if (source == ConversationSource.NATIVE && !backfill) expected else null)
    val gate = AlwaysOnVoicePolicy.PendingStart()
    verify(!gate.pending)
    verify(!gate.take(0, true))
    for (clean in listOf(false,true)) for (allowed in listOf(false,true)) for (elapsed in listOf(0L,89999L,90000L,90001L)) {
        gate.arm("r",100L)
        verify(gate.pending)
        if (clean) gate.nativeEnded("r")
        verify(gate.take(100L+elapsed, allowed) == (clean && allowed && elapsed < 90000L))
        verify(!gate.pending && !gate.take(100L+elapsed, true))
    }
    gate.arm("r",100); gate.nativeEnded("other"); verify(!gate.pending)
    gate.arm("r",100); gate.nativeEnded(null); verify(!gate.pending)
    gate.arm("r",100); gate.nativeEnded("r"); gate.cancel(); verify(!gate.take(101,true))
    gate.arm("r",100); verify(!gate.expire(90099)); verify(gate.pending); verify(gate.expire(90100)); verify(!gate.pending)
    gate.arm("r",100); gate.nativeEnded("r"); gate.arm("new",200); verify(!gate.take(201,true))
    verify(runCatching { gate.arm("",0) }.isFailure)
    println("$n assertions passed")
}
