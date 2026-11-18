// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import kotlin.math.sqrt

/** Registration uses a consistent set of utterances, not the meeting cluster count. */
internal object VoiceEnrollment {
    data class Sample(val beginMS: Long, val endMS: Long, val vector: FloatArray)
    data class Assessment(val seconds: Float, val totalSeconds: Float, val vectors: List<FloatArray>, val ready: Boolean, val message: String, val confirmationVectors: List<FloatArray> = emptyList())
    fun assess(samples: List<Sample>): Assessment {
        if (samples.isEmpty()) return Assessment(0f, 0f, emptyList(), false, "아직 선명한 음성을 추출하지 못했습니다. 음량 변화가 있는지 확인하고 자연스럽게 몇 마디 더 말해 주세요")
        val total = duration(samples) / 1000f
        // A short outlier must not poison the entire enrollment. Keep a mutually consistent set,
        // rather than lowering the separate identity-match threshold used by daily memory.
        val selected = samples.map { seed ->
            val compatible = samples.sortedByDescending { cosine(seed.vector, it.vector) }
            val group = mutableListOf(seed)
            for (sample in compatible) if (sample !== seed && group.all { cosine(it.vector, sample.vector) >= 0.5f }) group += sample
            group
        }.maxBy { duration(it) }
        val seconds = duration(selected) / 1000f
        val consistent = seconds >= total * 0.8f
        val ready = seconds >= 10 && selected.size >= 3 && consistent
        val message = when {
            ready -> "일관된 음성 ${seconds.toInt()}초 확보. 내 목소리를 저장할 수 있습니다"
            !consistent && total >= 10 -> "음성 일관성 부족: 일관 ${seconds.toInt()}초／전체 ${total.toInt()}초. 본인만 말하거나 다시 등록하세요"
            seconds < 10 -> "일관된 음성 ${seconds.toInt()}／10초 확보. 계속 말하되 문장마다 잠깐 쉬어 주세요"
            else -> "한 문장 더 말해 주세요. 등록에는 비교 가능한 음성 구간이 최소 세 개 필요합니다"
        }
        return Assessment(seconds, total, selected.sortedByDescending { it.endMS - it.beginMS }.take(10).map { it.vector }, ready, message,
            if (total >= 10 && samples.size >= 3) samples.sortedByDescending { it.endMS - it.beginMS }.take(10).map { it.vector } else emptyList())
    }
    private fun duration(samples: List<Sample>): Long {
        var end = Long.MIN_VALUE; var total = 0L
        for (sample in samples.sortedBy { it.beginMS }) { total += (sample.endMS - maxOf(sample.beginMS, end)).coerceAtLeast(0); end = maxOf(end, sample.endMS) }
        return total
    }
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return (dot / sqrt(aa * bb).coerceAtLeast(1e-12)).toFloat()
    }
}
