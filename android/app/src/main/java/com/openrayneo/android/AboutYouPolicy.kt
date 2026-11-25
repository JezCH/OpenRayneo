// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** User-entered profile only. Never merge inferred memories into this store. */
internal object AboutYouPolicy {
    const val MAX_FIELD_LENGTH = 500
    data class Profile(val about: String = "", val interests: String = "", val preferences: String = "",
                       val shareWithAgent: Boolean = false) {
        val hasContent: Boolean get() = about.isNotBlank() || interests.isNotBlank() || preferences.isNotBlank()
    }
    fun validated(profile: Profile): Profile {
        val cleaned = profile.copy(about = profile.about.trim(), interests = profile.interests.trim(),
            preferences = profile.preferences.trim())
        require(listOf(cleaned.about, cleaned.interests, cleaned.preferences).all { it.length <= MAX_FIELD_LENGTH }) {
            "개인화 정보는 항목당 500자 이하여야 합니다"
        }
        return cleaned.copy(shareWithAgent = cleaned.hasContent && cleaned.shareWithAgent)
    }
    fun forAgent(profile: Profile): Profile? =
        validated(profile).takeIf { it.shareWithAgent && it.hasContent }
    /** Explicitly selected fields only. A stale or revoked selection cannot be sent. */
    fun shareableFields(profile: Profile): Map<String, String> = forAgent(profile)?.let {
        linkedMapOf("about" to it.about, "interests" to it.interests, "preferences" to it.preferences).filterValues(String::isNotBlank)
    } ?: emptyMap()
    fun validateSelection(profile: Profile, selected: Map<String, String>): Map<String, String> {
        val current = shareableFields(profile)
        require(selected.all { (key, value) -> current[key] == value }) { "개인화 정보가 변경되었거나 공유 허용이 해제되었습니다" }
        return selected.toMap()
    }
}
