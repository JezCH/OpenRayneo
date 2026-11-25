// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Narrow, read-only commands for an explicitly awakened native assistant.
 * Unrecognized speech is left to the existing Agent; ambient speech is never a command.
 */
internal object LocalDeviceQuery {
    private val subjects = listOf("안경배터리", "안경배터리잔량", "안경의배터리", "레이네오배터리", "레이네오배터리잔량")
    private val endings = listOf("확인", "확인해줘", "알려줘", "얼마야", "얼마남았어", "몇퍼센트야", "몇퍼센트남았어", "몇프로야", "몇프로남았어", "몇%야", "몇%남았어")
    private val batteryQueries = subjects.flatMap { subject -> endings.map { subject + it } }.toSet()

    fun isBatteryQuery(text: String, source: ConversationSource, backfill: Boolean): Boolean {
        if (source != ConversationSource.NATIVE || backfill) return false
        val normalized = text.trim().trimEnd('?', '？', '.', '!', '。', '！').filterNot { it.isWhitespace() }
        return normalized in batteryQueries
    }

    fun batteryAnswer(percent: Int?): String = if (percent != null && percent in 0..100)
        "안경 배터리는 ${percent}%입니다." else "안경 배터리 잔량을 확인하지 못했습니다."
}
