// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** UTF-16 size matches the notification path. Never silently discard the rest. */
internal object ExternalHudText {
    const val MAX_INPUT = 4000
    const val PAGE_LENGTH = 100
    fun pages(input: String): List<String> {
        require(input.length <= MAX_INPUT)
        val clean = input.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        if (clean.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + PAGE_LENGTH, clean.length)
            if (end < clean.length && Character.isHighSurrogate(clean[end - 1]) && Character.isLowSurrogate(clean[end])) end--
            result.add(clean.substring(start, end))
            start = end
        }
        return result
    }
    fun validPage(text: String): Boolean = text.isNotBlank() && text.length <= PAGE_LENGTH && text.none { it.isISOControl() }
}
