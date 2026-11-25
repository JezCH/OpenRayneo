// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    for (about in listOf("", "  ", "업무")) for (interests in listOf("", "건축"))
        for (prefs in listOf("", "한국어")) for (share in listOf(false,true)) {
            val value = AboutYouPolicy.Profile(about, interests, prefs, share)
            val checked = AboutYouPolicy.validated(value)
            verify(checked.about == about.trim())
            verify(checked.shareWithAgent == (checked.hasContent && share))
            verify((AboutYouPolicy.forAgent(value) != null) == (checked.hasContent && share))
        }
    val limit = AboutYouPolicy.MAX_FIELD_LENGTH
    verify(AboutYouPolicy.validated(AboutYouPolicy.Profile(about = "가".repeat(limit))).about.length == limit)
    verify(runCatching { AboutYouPolicy.validated(AboutYouPolicy.Profile(about = "가".repeat(limit + 1))) }.isFailure)
    verify(runCatching { AboutYouPolicy.validated(AboutYouPolicy.Profile(interests = "가".repeat(limit + 1))) }.isFailure)
    verify(runCatching { AboutYouPolicy.validated(AboutYouPolicy.Profile(preferences = "가".repeat(limit + 1))) }.isFailure)
    verify(AboutYouPolicy.forAgent(AboutYouPolicy.Profile()) == null)
    println("$n assertions passed")
}
