// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import kotlin.math.roundToInt

/** Conservative Korean turn+distance text parser; never infer a turn from ETA or street names. */
internal object KoreanNavigationText {
    data class Instruction(val direction: String, val meters: Int)
    private val directions = linkedMapOf("좌측 전방" to "좌전방", "우측 전방" to "우전방",
        "왼쪽 앞" to "좌전방", "오른쪽 앞" to "우전방", "좌전방" to "좌전방", "우전방" to "우전방",
        "좌회전" to "좌회전", "우회전" to "우회전", "직진" to "직진", "유턴" to "유턴")
    private val direction = directions.keys.joinToString("|") { Regex.escape(it) }
    private const val distance = "([0-9]{1,4}(?:,[0-9]{3})?(?:\\.[0-9]+)?)\\s*(km|킬로미터|킬로|m|미터)"
    private val distanceFirst = Regex("^(?:전방\\s*)?$distance\\s*(?:앞|후|지점에서)?\\s*($direction)(?:하세요)?[.!]?$", RegexOption.IGNORE_CASE)
    private val actionFirst = Regex("^($direction)\\s*(?:까지)?\\s*$distance[.!]?$", RegexOption.IGNORE_CASE)
    fun parse(texts: List<String>): Instruction? {
        val matches = texts.mapNotNull { raw ->
            val line = KoreanLanguage.normalize(raw).trim().replace(Regex("\\s+"), " ")
            val a = distanceFirst.matchEntire(line)
            if (a != null) instruction(a.groupValues[3], a.groupValues[1], a.groupValues[2])
            else actionFirst.matchEntire(line)?.let { instruction(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }.distinct()
        return matches.singleOrNull()
    }
    private fun instruction(action: String, value: String, unit: String): Instruction? {
        val n = value.replace(",", "").toDoubleOrNull() ?: return null
        val meters = n * if (unit.lowercase() in setOf("km", "킬로미터", "킬로")) 1000 else 1
        if (!meters.isFinite() || meters !in 0.0..9999.0) return null
        return Instruction(directions.getValue(action), meters.roundToInt())
    }
}
