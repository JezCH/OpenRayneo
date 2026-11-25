// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android
import com.openrayneo.android.LocalDeviceQuery.Kind
import com.openrayneo.android.LocalDeviceQuery.Command
fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val cases = mapOf(
        "안경 자동 밝기 상태 알려줘" to Command(Kind.AUTO_BRIGHTNESS_READ),
        "레이네오 화면 높이 몇 단계야" to Command(Kind.DISPLAY_HEIGHT_READ),
        "안경 화면 높이를 5단계로 설정해줘" to Command(Kind.DISPLAY_HEIGHT_SET, 5),
        "안경 화면 거리 몇 단계야" to Command(Kind.DISPLAY_DISTANCE_READ),
        "안경 화면 거리 3단계로 맞춰줘" to Command(Kind.DISPLAY_DISTANCE_SET, 3),
        "안경 자동 절전 시간 몇 초야" to Command(Kind.AUTO_LOCK_READ),
        "레이네오 자동 잠금 시간을 60초로 설정해줘" to Command(Kind.AUTO_LOCK_SET, 60),
        "안경 자동 절전 999999999999초로 설정해줘" to Command(Kind.AUTO_LOCK_SET),
        "안경 화면 높이 0단계로 설정해줘" to Command(Kind.DISPLAY_HEIGHT_SET, 0),
        "안경 화면 높이 5단계로 설정하지 마" to null,
        "안경 자동 밝기 켜줘" to null,
        "안경 화면 거리 2단계로 바꿔줘 그리고 이메일 보내줘" to null,
        "휴대폰 자동 잠금 시간 몇 초야" to null,
        "안경 자동 밝기 상태 알려줘라고 말했어" to null
    )
    for ((speech, expected) in cases) for (source in ConversationSource.values()) for (backfill in listOf(false,true))
        verify(LocalDeviceQuery.parse(speech,source,backfill) == if (source == ConversationSource.NATIVE && !backfill) expected else null)
    for (v in listOf(null,-1,0,1,2,3,4,5,6,7,10,15,25,40,60,120,121)) {
        verify(LocalDeviceQuery.validSetting(Kind.DISPLAY_HEIGHT_SET,v) == (v != null && v in 1..6))
        verify(LocalDeviceQuery.validSetting(Kind.DISPLAY_DISTANCE_SET,v) == (v != null && v in 1..3))
        verify(LocalDeviceQuery.validSetting(Kind.AUTO_LOCK_SET,v) == (v in setOf(5,10,15,25,40,60,120)))
    }
    println("$n assertions passed")
}
