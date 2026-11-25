// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.text.Normalizer
import java.util.Locale

/** Android port of the repository's macOS TeleprompterSpeechMatcher. Wire offsets are UTF-8 bytes. */
internal class TeleprompterSpeechMatcher(text: String) {
    data class Match(val byteOffset: Int, val confidence: Double)
    private val characters: IntArray
    private val ends: IntArray
    val totalBytes = text.toByteArray(Charsets.UTF_8).size
    private var cursor = 0
    private var offset = 0
    private var previous = IntArray(0)
    init {
        val chars = ArrayList<Int>(); val offsets = ArrayList<Int>()
        var bytes = 0
        text.codePoints().forEach { cp ->
            val scalar = String(Character.toChars(cp)); bytes += scalar.toByteArray(Charsets.UTF_8).size
            normalize(scalar).forEach { chars.add(it); offsets.add(bytes) }
        }
        characters = chars.toIntArray(); ends = offsets.toIntArray()
    }
    fun seek(byteOffset: Int) {
        val clamped = byteOffset.coerceIn(0, totalBytes)
        cursor = ends.indexOfFirst { it > clamped }.let { if (it < 0) ends.size else it }
        offset = if (clamped == totalBytes) totalBytes else if (cursor > 0) ends[cursor - 1] else 0
        previous = IntArray(0)
    }
    fun match(text: String, final: Boolean = false): Match? {
        val query = normalize(text.takeLast(512)).takeLast(72).toIntArray()
        val duplicate = previous.contentEquals(query)
        previous = if (final) IntArray(0) else query
        if (duplicate || query.size < 4 || characters.isEmpty()) return null
        val lower = maxOf(0, cursor - maxOf(90, query.size + 12))
        val upper = minOf(characters.size, cursor + 100)
        val count = upper - lower
        if (count <= 0) return null
        var costs = IntArray(count + 1)
        var starts = IntArray(count + 1) { it }
        for (row in query.indices) {
            val current = IntArray(count + 1); val nextStarts = IntArray(count + 1)
            current[0] = row + 1
            for (column in 0 until count) {
                val sub = costs[column] + if (query[row] == characters[lower + column]) 0 else 1
                val del = costs[column + 1] + 1; val ins = current[column] + 1
                if (sub <= del && sub <= ins) { current[column + 1] = sub; nextStarts[column + 1] = starts[column] }
                else if (del <= ins) { current[column + 1] = del; nextStarts[column + 1] = starts[column + 1] }
                else { current[column + 1] = ins; nextStarts[column + 1] = nextStarts[column] }
            }
            costs = current; starts = nextStarts
        }
        // Prefer the nearest equally good occurrence, including the current cursor (no repeat jump).
        val end = (1..count).filter { lower + it >= cursor }.minWithOrNull(compareBy<Int> { costs[it] }.thenBy { lower + it - cursor }) ?: return null
        val confidence = 1.0 - costs[end].toDouble() / query.size
        val advance = lower + end - cursor
        if (confidence < 0.72 || end - starts[end] < maxOf(3, query.size * 2 / 3) ||
            advance <= 0 || advance > maxOf(24, query.size * 2) || lower + starts[end] > cursor + 36) return null
        cursor = lower + end
        val next = ends[cursor - 1]
        if (next <= offset) return null
        offset = next
        return Match(next, confidence)
    }
    companion object {
        private fun normalize(s: String): IntArray = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).codePoints().filter { Character.isLetterOrDigit(it) }.toArray()
        fun pageStart(lineStarts: IntArray, offset: Int): Int {
            val index = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
            return lineStarts[(index - 1).coerceAtLeast(0)]
        }
    }
}
