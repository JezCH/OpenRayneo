// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android
import com.openrayneo.android.FocusPolicy.Command
import com.openrayneo.android.FocusPolicy.Output
fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val cases = mapOf(
        "안경 집중 모드 켜줘" to Command.FOCUS_ON, "레이네오 집중 모드 시작해줘!" to Command.FOCUS_ON,
        "안경 방해 금지 켜줘" to Command.FOCUS_ON, "안경 집중 모드 꺼줘" to Command.FOCUS_OFF,
        "안경 집중 모드 종료해줘" to Command.FOCUS_OFF, "안경 방해 금지 꺼줘" to Command.FOCUS_OFF,
        "안경 집중 모드 상태 알려줘" to Command.FOCUS_READ, "안경 알림 켜줘" to Command.NOTIFICATIONS_ON,
        "안경 알림 전송 켜줘" to Command.NOTIFICATIONS_ON, "안경 알림 꺼줘" to Command.NOTIFICATIONS_OFF,
        "안경 알림 전송 꺼줘" to Command.NOTIFICATIONS_OFF, "안경 알림 상태 알려줘" to Command.NOTIFICATIONS_READ,
        "휴대폰 집중 모드 켜줘" to null, "안경 집중 모드 켜줘라고 했어" to null,
        "안경 집중 모드 켜지 마" to null, "안경 알림 꺼줘 그리고 메일 보내줘" to null, "집중 모드 켜줘" to null)
    for ((speech, expected) in cases) for (source in ConversationSource.entries) for (backfill in listOf(false,true))
        verify(FocusPolicy.parse(speech,source,backfill) == if (source == ConversationSource.NATIVE && !backfill) expected else null)
    val s = FocusPolicy.State()
    verify(!s.focused && s.notificationsEnabled)
    val initial = s.generation
    verify(s.acceptsHint(initial))
    s.apply(Command.FOCUS_ON)
    verify(!s.acceptsHint(initial))
    val focused = s.generation
    s.apply(Command.FOCUS_ON); verify(s.generation == focused)
    s.apply(Command.FOCUS_READ); verify(s.generation == focused)
    verify(s.allows(Output.DIRECT_ANSWER) && s.allows(Output.TIMER_NOTIFICATION))
    s.apply(Command.FOCUS_OFF); verify(!s.acceptsHint(initial) && !s.acceptsHint(focused) && s.acceptsHint(s.generation))
    for (f in listOf(false,true)) for (enabled in listOf(false,true)) {
        s.apply(if(f) Command.FOCUS_ON else Command.FOCUS_OFF)
        s.apply(if(enabled) Command.NOTIFICATIONS_ON else Command.NOTIFICATIONS_OFF)
        verify(s.focused == f && s.notificationsEnabled == enabled)
        verify(s.allows(Output.PASSIVE_HINT) == (enabled && !f))
        verify(s.allows(Output.GENERAL_NOTIFICATION) == (enabled && !f))
        verify(s.allows(Output.TIMER_NOTIFICATION) == enabled)
        verify(s.allows(Output.DIRECT_ANSWER))
        verify(s.acceptsHint(s.generation) == (enabled && !f))
    }
    val old = s.generation
    s.reset(); verify(!s.focused && s.notificationsEnabled && !s.acceptsHint(old) && s.acceptsHint(s.generation))
    println("$n assertions passed")
}
