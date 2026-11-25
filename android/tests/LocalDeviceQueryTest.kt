// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val cases = mapOf(
        "안경 배터리 얼마야?" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BATTERY),
        "레이네오 배터리 잔량 몇 % 남았어" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BATTERY),
        "안경 밝기 몇 단계야?" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_READ),
        "안경 화면 밝기를 높여줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_UP),
        "레이네오 밝기 한 단계 내려줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_DOWN),
        "안경 밝기 7단계로 설정해줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_SET, 7),
        "안경 밝기 0단계로 맞춰줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_SET, 0),
        "안경 밝기 18단계로 바꿔줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_SET, 18),
        "안경 밝기 9999999999999999999단계로 맞춰줘" to LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_SET),
        "안경 밝기 올려줘 그리고 메일 보내줘" to null,
        "안경 밝기 올려줘라고 말했어" to null,
        "안경 밝기 올려주지 마" to null,
        "휴대폰 밝기 올려줘" to null,
        "안경 밝기 50%로 바꿔줘" to null,
        "\"안경 밝기 올려줘\"" to null,
        "안경 밝기 내리지 마" to null
    )
    for ((speech, expected) in cases) for (source in ConversationSource.values()) for (backfill in listOf(false, true)) {
        verify(LocalDeviceQuery.parse(speech, source, backfill) == if (source == ConversationSource.NATIVE && !backfill) expected else null)
    }
    for (current in listOf(null, -1, 0, 1, 2, 8, 16, 17, 18, Int.MAX_VALUE)) {
        val valid = current != null && current in 1..17
        verify(LocalDeviceQuery.target(LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_UP), current) == if (valid) (current!! + 1).coerceAtMost(17) else null)
        verify(LocalDeviceQuery.target(LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_DOWN), current) == if (valid) (current!! - 1).coerceAtLeast(1) else null)
        verify(LocalDeviceQuery.target(LocalDeviceQuery.Command(LocalDeviceQuery.Kind.BRIGHTNESS_SET, current), null) == if (valid) current else null)
    }
    verify(LocalDeviceQuery.brightnessAnswer(17) == "안경 밝기는 17단계입니다.")
    verify(LocalDeviceQuery.brightnessAnswer(0).contains("확인하지 못했습니다"))
    verify(LocalDeviceQuery.batteryAnswer(0) == "안경 배터리는 0%입니다.")
    println("$n assertions passed")
}
