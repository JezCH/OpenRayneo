// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Missing-key defaults only. Never silently enable capture or external inference. */
internal object FeatureDefaults {
    val safe = mapOf(
        "answerLanguage" to "ko-KR", "phoneSpeechLanguage" to "ko-KR",
        "ttsOutput" to "headphones", "asrKeepLoaded" to "true",
        "memorySuggestOnSend" to "true", "memorySuggestionDays" to "30",
        "memorySuggestionLimit" to "3", "memoryAutoSummary" to "false",
        "audioRetention" to "transcript_only"
    )
    /** Requires explicit confirmation. Existing explicit values, even false, win. */
    val recommended = mapOf(
        "nativeAutoAnswer" to "true", "ambientWakeEnabled" to "true",
        "ambientWakeContext" to "true", "fastRealtimeHints" to "true"
    )
    fun missingDefaults(has: (String) -> Boolean): Map<String, String> =
        safe.filterKeys { !has(it) }
    fun recommendedChanges(has: (String) -> Boolean): Map<String, String> =
        recommended.filterKeys { !has(it) }
    fun changedDrafts(keys: List<String>, baseline: Map<String, String>, drafts: Map<String, String>): Map<String, String> =
        keys.mapNotNull { key -> drafts[key]?.let { value ->
            if (value != baseline[key]) key to value else null
        } }.toMap()
}
