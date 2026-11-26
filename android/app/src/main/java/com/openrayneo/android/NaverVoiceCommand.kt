// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Explicit wake requests only. A place name is not a verified coordinate or route. */
internal object NaverVoiceCommand {
    enum class Mode { UNSPECIFIED, WALK, CAR, PUBLIC }
    enum class Control { STOP_HUD, START_HUD, CANCEL_REQUEST, STATUS, STOP_MAP_UNSUPPORTED }
    fun control(raw: String): Control? {
        val text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .filterNot(Char::isWhitespace).trimEnd('.', '!', '?', '。', '！', '？')
        return when (text) {
            "안경길안내종료", "안경길안내종료해줘", "안경길안내중지", "안경길안내중지해줘",
            "안경내비게이션종료", "안경내비게이션종료해줘", "안경안내꺼줘" -> Control.STOP_HUD
            "안경길안내시작", "안경길안내시작해줘", "안경길안내재개", "안경길안내재개해줘",
            "안경내비게이션재개", "안경내비게이션재개해줘" -> Control.START_HUD
            "길찾기취소", "길찾기취소해줘", "길안내요청취소", "길안내요청취소해줘" -> Control.CANCEL_REQUEST
            "길안내상태", "길안내상태알려줘", "내비게이션상태알려줘", "안경길안내상태" -> Control.STATUS
            "길안내종료", "길안내종료해줘", "길안내중지해줘", "내비게이션종료", "내비게이션종료해줘",
            "네이버지도길안내종료", "네이버지도길안내종료해줘" -> Control.STOP_MAP_UNSUPPORTED
            else -> null
        }
    }
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
