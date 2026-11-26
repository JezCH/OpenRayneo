// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Explicit wake requests only. A place name is not a verified coordinate or route. */
internal object NaverVoiceCommand {
    enum class Mode { UNSPECIFIED, WALK, CAR, PUBLIC }
    data class Request(val destination: String, val mode: Mode, val routeRequested: Boolean = true)
    private val prefix = Regex("^(?:네이버\\s*지도(?:에서|로)?\\s+)")
    private val route = Regex("^(.+?)(?:까지|으로|로)\\s*(?:(걸어서|도보로|차로|자동차로|대중교통으로)\\s*)?(?:길\\s*(?:찾기|안내)(?:를)?\\s*(?:해\\s*줘|해\\s*주세요|시작해\\s*줘|시작해\\s*주세요|해)|안내\\s*(?:해\\s*줘|해\\s*주세요|해)|데려다\\s*줘)$")
    private val search = Regex("^(.+?)\\s+(?:검색|찾아)\\s*(?:해\\s*)?(?:줘|주세요)$")
    fun parse(raw: String): Request? {
        val text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .trim().trimEnd('.', '!', '?', '。', '！', '？').trim()
            .replace(Regex("\\s+"), " ")
        val explicitMap = prefix.containsMatchIn(text)
        val input = prefix.replaceFirst(text, "")
        val match = route.matchEntire(input)
        val place = (match?.groupValues?.get(1) ?: if (explicitMap) search.matchEntire(input)?.groupValues?.get(1) else null)
            ?.trim()?.takeIf { it.length in 2..120 && it.none(Char::isISOControl) } ?: return null
        if (place in setOf("여기", "거기", "저기", "어디", "길", "지도", "집", "회사", "이전 목적지")) return null
        val mode = when (match?.groupValues?.get(2)) {
            "걸어서", "도보로" -> Mode.WALK
            "차로", "자동차로" -> Mode.CAR
            "대중교통으로" -> Mode.PUBLIC
            else -> Mode.UNSPECIFIED
        }
        return Request(place, mode, routeRequested = match != null)
    }
}
