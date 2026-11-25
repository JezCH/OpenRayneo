// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.text.Normalizer
import java.util.Locale

/** Presentation only: never translate device protocol values or persisted speaker identifiers. */
internal object KoreanLanguage {
    const val DEFAULT_LOCALE = "ko-KR"
    const val DEFAULT_ASR = "ko"
    val answerOptions = listOf("ko-KR" to "한국어", "en-US" to "영어", "ja-JP" to "일본어", "zh-CN" to "중국어(간체)")
    fun answerLanguage(saved: String) = saved.takeIf { code -> answerOptions.any { it.first == code } } ?: DEFAULT_LOCALE
    fun answerPolicy(saved: String): String {
        val language = answerOptions.first { it.first == answerLanguage(saved) }.second
        return "[응답 언어] 기본 답변 언어는 $language 입니다. 사용자가 다른 언어를 명시하거나 번역을 요청한 경우 그 요청을 따르세요. 원문 인용, 이름, 코드, JSON 키와 값의 계약, 도구 인수는 언어 정책 때문에 바꾸지 마세요."
    }
    fun withAnswerPolicy(context: String, saved: String) = answerPolicy(saved) + "\n" + context
    val phoneInputOptions = listOf("ko-KR" to "한국어", "en-US" to "영어", "zh-CN" to "중국어(간체)")
    fun phoneInput(saved: String): String = saved.takeIf { code -> phoneInputOptions.any { it.first == code } } ?: DEFAULT_LOCALE
    fun isKoreanTag(value: String): Boolean = value.trim().replace('_', '-').lowercase(Locale.ROOT).let {
        it == "ko" || it.startsWith("ko-") || it == "korean" || it == "한국어" || it == "한국말" || it == "韩语" || it == "韓語"
    }
    fun deviceLanguageLabel(value: String): String {
        if (isKoreanTag(value)) return "한국어 ($value)"
        val normalized = value.trim().replace('_', '-')
        val known = mapOf("english" to "영어", "chinese" to "중국어", "japanese" to "일본어",
            "french" to "프랑스어", "german" to "독일어", "spanish" to "스페인어", "italian" to "이탈리아어",
            "简体中文" to "중국어(간체)", "繁體中文" to "중국어(번체)", "中文" to "중국어", "日本語" to "일본어")
        val label = known[normalized.lowercase(Locale.ROOT)] ?: Locale.forLanguageTag(normalized).let {
            val name = it.getDisplayName(Locale.KOREAN)
            name.takeIf { name -> it.language.length in 2..3 && name.isNotBlank() && name != normalized }
        }
        return if (label.isNullOrBlank()) value else "$label ($value)"
    }
    fun speakerLabel(value: String): String = when (value) {
        "未知" -> "알 수 없는 화자"
        "登记" -> "등록 중인 화자"
        else -> value
    }
    fun matchReason(value: String): String = when (value) {
        "手动标注" -> "직접 지정"
        else -> value
    }
    fun memoryKind(value: String): String = when (value) {
        "陈述", "陳述" -> "사실 진술"
        "计划", "計劃" -> "계획"
        "转述", "轉述" -> "전언"
        "不确定", "不確定" -> "불확실"
        else -> value
    }
    /** Normalize Hangul composition without modifying original saved or spoken text. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
}
