// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal enum class AnswerSource { MOBILE_AGENT, COMPUTER_CODEX }
internal data class AnswerRef(val source: AnswerSource, val taskId: Long)
internal data class AnswerContent(val ref: AnswerRef, val question: String, val text: String)
internal enum class ReturnTarget { IDLE, PROMPT_STANDBY, NAVIGATION }
internal sealed interface DisplayIntent {
    data object Idle : DisplayIntent
    data object PromptStandby : DisplayIntent
    data object BlankPrompt : DisplayIntent
    data object Navigation : DisplayIntent
    /** Reserved stock 0x20 screen; never open the legacy 0x13 page. */
    data object NativeNavigation : DisplayIntent
    data object Translation : DisplayIntent
    data class Answer(val ref: AnswerRef, val returnTo: ReturnTarget) : DisplayIntent
}
internal enum class DisplayOwnership { APP, YIELDED }
internal enum class LinkPhase { DISCONNECTED, SCANNING, CONNECTING, BLE_ONLY, FULL, RETRY_WAIT, CLOSING }
internal data class LinkState(val phase: LinkPhase = LinkPhase.DISCONNECTED, val detail: String = "안경 연결 대기 중",
                              val lastFailure: String? = null, val reconnectWaiting: Boolean = false)
internal enum class PageMode { NAVIGATION, PROMPT }
internal enum class PagePhase { STARTING, LAYOUT, SETTLING, READY, CLOSING }
internal data class PageContent(val key: String, val text: String, val answer: String? = null, val final: Boolean = true)
internal data class PageSession(
    val id: Long, val mode: PageMode, val phase: PagePhase, val content: PageContent,
    val writtenContent: PageContent? = null,
    val receivesGlassesAudio: Boolean = true,
    val nativeSubtitle: NativeSubtitleStart? = null,
    val nativeHint: NativeHintStart? = null,
    val nativePaused: Boolean = false,
)
internal data class TranslationView(val source: String = "", val target: String = "", val final: Boolean = true)
internal data class NavigationState(val enabled: Boolean = false, val key: String? = null, val frame: String? = null)
internal enum class AudioPhase { STOPPED, WAITING_PACKET, WAITING_PCM, RECEIVING, STALLED, FAILED }
internal data class AudioState(val phase: AudioPhase = AudioPhase.STOPPED, val detail: String = "안경 음성 수신이 중지됨")
internal data class AnswerPermit(val ref: AnswerRef, val revision: Long)
internal data class GlassesState(
    val intent: DisplayIntent = DisplayIntent.Idle,
    val ownership: DisplayOwnership = DisplayOwnership.APP,
    val link: LinkState = LinkState(),
    val navigation: NavigationState = NavigationState(),
    val session: PageSession? = null,
    val audio: AudioState = AudioState(),
    val answer: AnswerContent? = null,
    val translation: TranslationView = TranslationView(),
    val captionMode: Boolean = false,
    val mediaCaptionMode: Boolean = false,
    /** Live caption on the realtime-hint standby page: last heard sentence and a status line. */
    val standby: TranslationView = TranslationView(),
    val revision: Long = 0,
    val nextSession: Long = 1,
    val answerPermit: AnswerPermit? = null,
    val restoreRevision: Long? = null,
    val blockedRevision: Long? = null,
    val error: String? = null,
    val disconnecting: Boolean = false,
) {
    val displayDescription: String get() = when {
        disconnecting -> "안경 세션 닫는 중"
        ownership == DisplayOwnership.YIELDED -> "안경 표시를 양보했습니다. 재개할 기능을 선택하세요"
        error != null -> error
        session?.phase == PagePhase.CLOSING -> "이전 페이지 닫는 중"
        link.phase == LinkPhase.BLE_ONLY -> "BLE 진단 연결 전용, 표시나 음성 수신은 시작하지 않음"
        link.phase != LinkPhase.FULL -> link.detail
        intent == DisplayIntent.NativeNavigation -> "순정 내비 시작·종료 시험 중"
        session == null -> "연결됨, 안경 표시 유휴"
        session.nativePaused -> "안경 순정 세션 일시정지 · 음성 수신 중지"
        session.phase == PagePhase.STARTING -> "안경의 시작 확인 대기"
        session.phase == PagePhase.LAYOUT -> "내비게이션 레이아웃 확인 중"
        (session.nativeSubtitle != null || session.nativeHint != null) && audio.phase == AudioPhase.WAITING_PACKET -> "안경 시작 응답 전송 · 음성 수신 대기"
        session.phase == PagePhase.SETTLING -> if (session.nativeSubtitle != null || session.nativeHint != null) "안경 시작 응답 전송 · 실제 표시 미확인" else "페이지 확인됨, 내용 전송 중"
        session.writtenContent != session.content -> "안경 내용 업데이트 중"
        session.mode == PageMode.NAVIGATION -> "내비게이션 세션 준비됨"
        intent == DisplayIntent.BlankPrompt -> "화면을 지웠고 음성 수신은 유지됨"
        intent is DisplayIntent.Answer -> "답변 세션 준비됨"
        intent == DisplayIntent.Translation -> if (mediaCaptionMode) "미디어 자막 표시 준비됨" else if (captionMode) "실시간 자막 세션 준비됨" else "실시간 번역 세션 준비됨"
        else -> "실시간 힌트 세션 준비됨"
    }
}
internal sealed interface PageSignal {
    data object NativePaused : PageSignal
    data class NativeResumed(val channels: Int) : PageSignal
    data class AudioAwaitingPeer(val channels: Int) : PageSignal
    data class AudioAccepted(val channels: Int) : PageSignal
    data object LayoutPending : PageSignal
    data object Settling : PageSignal
    data class Written(val content: PageContent) : PageSignal
    data class Failed(val message: String) : PageSignal
    data class Ended(val message: String) : PageSignal
    data class Closed(val error: String? = null) : PageSignal
}
internal sealed interface GlassesEvent {
    data object BeginConnection : GlassesEvent
    data class LinkChanged(val state: LinkState) : GlassesEvent
    data class NavigationChanged(val state: NavigationState) : GlassesEvent
    data class SelectNavigation(val state: NavigationState) : GlassesEvent
    data class ResumeNavigation(val key: String) : GlassesEvent
    data object StopNavigation : GlassesEvent
    data object ReserveNativeNavigation : GlassesEvent
    data object ReleaseNativeNavigation : GlassesEvent
    data object PauseDisplay : GlassesEvent
    data object OpenStandby : GlassesEvent
    data object StartTranslation : GlassesEvent
    data class StartNativeSubtitle(val request: NativeSubtitleStart) : GlassesEvent
    data class StartNativeHint(val request: NativeHintStart) : GlassesEvent
    data object StartCaption : GlassesEvent
    data object StartMediaCaption : GlassesEvent
    data class TranslationText(val view: TranslationView) : GlassesEvent
    data class StandbyText(val view: TranslationView) : GlassesEvent
    data object ClearDisplay : GlassesEvent
    data class ForgetAnswer(val source: AnswerSource) : GlassesEvent
    data object RetryAudio : GlassesEvent
    data object DismissAnswer : GlassesEvent
    data object Disconnect : GlassesEvent
    data class AnswerRequested(val ref: AnswerRef) : GlassesEvent
    data class CancelAnswer(val source: AnswerSource) : GlassesEvent
    data class AnswerCompleted(val content: AnswerContent, val permit: AnswerPermit) : GlassesEvent
    data class RevealAnswer(val content: AnswerContent) : GlassesEvent
    data class SessionSignal(val id: Long, val signal: PageSignal) : GlassesEvent
    data class AudioChanged(val id: Long, val state: AudioState) : GlassesEvent
    data class RestoreAnswer(val id: Long, val revision: Long, val ref: AnswerRef) : GlassesEvent
    data class CloseTimedOut(val id: Long) : GlassesEvent
    data class ExternalPage(val description: String) : GlassesEvent
}
internal sealed interface GlassesEffect {
    data class StartPage(val session: PageSession) : GlassesEffect
    data class UpdatePage(val id: Long, val content: PageContent) : GlassesEffect
    data class ClosePage(val id: Long) : GlassesEffect
    data object ResetPage : GlassesEffect
    data class StartAudio(val id: Long, val channels: Int) : GlassesEffect
    data object StopAudio : GlassesEffect
    data class FollowNavigation(val key: String) : GlassesEffect
    data object StopFollowing : GlassesEffect
    data class ScheduleRestore(val id: Long, val revision: Long, val ref: AnswerRef) : GlassesEffect
    data object CancelRestore : GlassesEffect
    data class ScheduleCloseTimeout(val id: Long) : GlassesEffect
    data object CancelCloseTimeout : GlassesEffect
    data object DisconnectTransport : GlassesEffect
}
internal data class GlassesTransition(val state: GlassesState, val effects: List<GlassesEffect>)
