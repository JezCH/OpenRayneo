// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal object MediaTranslationRoute {
    const val BILINGUAL = "ko-en-bidi"
    fun steps(language: String): List<String> = when (language) {
        BILINGUAL -> listOf("en-ko", "ko-en")
        "ko" -> emptyList()
        "en" -> listOf("en-ko")
        "ja" -> listOf("ja-en", "en-ko")
        "zh" -> listOf("zh-en", "en-ko")
        else -> throw IllegalArgumentException("지원하지 않는 미디어 원문 언어")
    }
    fun required(language: String, requested: Boolean) = requested && steps(language).isNotEmpty()
}
internal enum class LocalMediaTranslationPhase {
    NOT_PREPARED, VERIFYING, DOWNLOADING, LOADING, READY, CANCELLING, ERROR
}
internal data class LocalMediaTranslationState(
    val phase: LocalMediaTranslationPhase = LocalMediaTranslationPhase.NOT_PREPARED,
    val language: String = "",
    val detail: String = "내장 번역 모델 준비 필요",
    val received: Long = 0,
    val total: Long = 0,
    val completed: Long = 0,
    val skipped: Long = 0,
    val lastMs: Long? = null,
) {
    val preparing get() = phase in setOf(LocalMediaTranslationPhase.VERIFYING, LocalMediaTranslationPhase.DOWNLOADING,
        LocalMediaTranslationPhase.LOADING, LocalMediaTranslationPhase.CANCELLING)
    fun readyFor(language: String) = phase == LocalMediaTranslationPhase.READY && this.language == language
}
