// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** One route-title catalogue and deterministic stack transitions. */
internal object CompanionRoutePolicy {
    val titles: Map<String, String> = linkedMapOf(
        "settings" to "설정",
        "feature-controls" to "자동 기능 관리",
        "system-status" to "시스템 현황",
        "app-update" to "앱 업데이트",
        "local-clock" to "타이머 및 스톱워치",
        "about-you" to "개인화 정보",
        "model" to "AI 모델 선택",
        "computer" to "컴퓨터 연결",
        "diagnostics" to "기기 및 진단",
        "voice-settings" to "음성·기억 설정",
        "voice-profiles" to "목소리 및 인물",
        "device-settings" to "안경 설정",
        "content" to "홈 화면 및 콘텐츠",
        "teleprompter" to "프롬프터 자료",
        "agent-api" to "AI 연결 및 동기화",
        "navigation" to "길 안내",
        "display-controls" to "안경 화면 및 오디오",
        "translation" to "실시간 번역",
        "captions" to "주변 대화 자막",
        "media-captions" to "유튜브·미디어 자막",
        "korean-wake" to "한국어 호출",
        "korean-glyphs" to "한글 표시 검사",
        "phone-notifications" to "휴대폰 알림",
        "record-migration" to "기존 기록 가져오기",
    )

    fun title(target: String, validation: Boolean = false): String? =
        titles[target] ?: if (validation && target == "external-hud") "외부 텍스트 안경 표시" else null

    fun open(stack: List<String>, target: String, validation: Boolean = false): List<String> {
        if (title(target, validation) == null) return stack
        val existing = stack.indexOf(target)
        return if (existing >= 0) stack.take(existing + 1) else stack + target
    }

    fun back(stack: List<String>): List<String> = stack.dropLast(1)
    fun removed(previous: List<String>, next: List<String>): List<String> = previous.filterNot { it in next }
}
