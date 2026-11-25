// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android
import com.openrayneo.android.LocalClockPolicy.Kind
import com.openrayneo.android.LocalClockPolicy.Command
fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val cases = mapOf(
        "안경 타이머 5분 시작해줘" to Command(Kind.TIMER_START,300000),
        "레이네오 1분 30초 타이머 설정해줘!" to Command(Kind.TIMER_START,90000),
        "안경 타이머 1시간 2분 3초로 맞춰줘" to Command(Kind.TIMER_START,3723000),
        "안경 타이머 24시간 시작해줘" to Command(Kind.TIMER_START,86400000),
        "안경 타이머 25시간 시작해줘" to Command(Kind.TIMER_START),
        "안경 타이머 0초 설정해줘" to Command(Kind.TIMER_START),
        "안경 타이머 999999999999999999999초 설정해줘" to Command(Kind.TIMER_START),
        "안경 타이머 남은 시간 알려줘" to Command(Kind.TIMER_READ),
        "안경 타이머 취소해줘" to Command(Kind.TIMER_CANCEL),
        "안경 스톱워치 시작해줘" to Command(Kind.WATCH_START),
        "안경 스톱워치 시간 알려줘" to Command(Kind.WATCH_READ),
        "안경 스톱워치 중지해줘" to Command(Kind.WATCH_STOP),
        "안경 스톱워치 초기화해줘" to Command(Kind.WATCH_RESET),
        "휴대폰 타이머 5분 시작해줘" to null,
        "안경 타이머 5분 시작하지 마" to null,
        "안경 타이머 5분 시작해줘라고 했어" to null,
        "안경 타이머 5분 시작해줘 그리고 이메일 보내줘" to null)
    for ((speech, expected) in cases) for (source in ConversationSource.entries) for (backfill in listOf(false,true))
        verify(LocalClockPolicy.parse(speech,source,backfill) == if (source == ConversationSource.NATIVE && !backfill) expected else null)
    val clock = LocalClockPolicy.Clock()
    verify(!clock.timerRunning && !clock.watchRunning)
    verify(clock.execute(Command(Kind.TIMER_START,0),0).contains("지정"))
    verify(!clock.timerRunning)
    verify(clock.execute(Command(Kind.TIMER_START,10000),100).contains("10초"))
    verify(clock.timerRunning)
    verify(clock.execute(Command(Kind.TIMER_START,20000),200).contains("이미"))
    verify(clock.execute(Command(Kind.TIMER_READ),101).contains("10초"))
    verify(!clock.poll(10099))
    verify(clock.poll(10100))
    verify(!clock.poll(10101) && !clock.timerRunning)
    verify(clock.execute(Command(Kind.TIMER_READ),10101).contains("없습니다"))
    clock.execute(Command(Kind.TIMER_START,1000),11000)
    verify(clock.execute(Command(Kind.TIMER_CANCEL),11001).contains("취소"))
    verify(!clock.poll(12000))
    verify(clock.execute(Command(Kind.WATCH_START),20000).contains("시작"))
    verify(clock.watchRunning)
    verify(clock.execute(Command(Kind.WATCH_START),22000).contains("이미"))
    verify(clock.execute(Command(Kind.WATCH_READ),23000).contains("3초"))
    clock.execute(Command(Kind.TIMER_START,1000),23000)
    verify(clock.poll(24000) && clock.watchRunning)
    verify(clock.execute(Command(Kind.WATCH_STOP),25000).contains("5초"))
    verify(!clock.watchRunning)
    verify(clock.execute(Command(Kind.WATCH_READ),35000).contains("5초"))
    clock.execute(Command(Kind.WATCH_START),40000)
    verify(clock.execute(Command(Kind.WATCH_READ),42000).contains("7초"))
    verify(clock.execute(Command(Kind.WATCH_RESET),43000).contains("초기화"))
    verify(clock.execute(Command(Kind.WATCH_READ),44000).contains("0초"))
    clock.execute(Command(Kind.WATCH_START),45000); clock.execute(Command(Kind.TIMER_START,5000),45000)
    clock.clear(); verify(!clock.timerRunning && !clock.watchRunning && !clock.poll(60000))
    verify(clock.execute(Command(Kind.WATCH_READ),60000).contains("0초"))
    println("$n assertions passed")
}
