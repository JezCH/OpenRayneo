// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Strict numerical tokens in explicit Korean device commands, not general natural-language execution. */
internal object KoreanCommandNumbers {
    const val TOKEN = "[0-9]+|[영공일이삼사오육칠팔구십백천만한하나두둘세셋네넷다섯여섯일곱여덟아홉열스물스무서른마흔쉰예순일흔여든아흔]+"
    private val digits = "영일이삼사오육칠팔구".withIndex().associate { it.value to it.index } + ('공' to 0)
    private val nativeOnes = mapOf("한" to 1, "하나" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3,
        "네" to 4, "넷" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9)
    private val nativeTens = linkedMapOf("열" to 10, "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40,
        "쉰" to 50, "예순" to 60, "일흔" to 70, "여든" to 80, "아흔" to 90)

    fun parse(value: String): Int? {
        val token = KoreanLanguage.normalize(value).trim()
        if (token.isEmpty()) return null
        if (token.all { it in '0'..'9' }) return token.toIntOrNull()
        nativeOnes[token]?.let { return it }
        for ((prefix, tens) in nativeTens) {
            if (token == prefix) return tens
            if (prefix != "스무" && token.startsWith(prefix))
                return nativeOnes[token.removePrefix(prefix)]?.let { tens + it }
        }
        if (token.length == 1) return digits[token[0]]
        val sections = token.split('만')
        if (sections.size > 2) return null
        if (sections.size == 1) return section(token)
        val high = if (sections[0].isEmpty()) 1 else section(sections[0]) ?: return null
        val low = if (sections[1].isEmpty()) 0 else section(sections[1]) ?: return null
        if (high <= 0 || high > 9) return null
        return high * 10_000 + low
    }
    private fun section(value: String): Int? {
        val units = mapOf('천' to 1000, '백' to 100, '십' to 10)
        var total = 0
        var previousUnit = 10_000
        var pending: Int? = null
        for (ch in value) {
            val digit = digits[ch]
            if (digit != null) {
                if (digit == 0 || pending != null) return null
                pending = digit
            } else {
                val unit = units[ch] ?: return null
                if (unit >= previousUnit) return null
                total += (pending ?: 1) * unit
                previousUnit = unit
                pending = null
            }
        }
        return total + (pending ?: 0)
    }
}
