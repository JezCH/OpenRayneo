// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Pure decision logic. The service commits the state before executing effects or accepting callbacks. */
internal object GlassesCoordinator {
    fun reduce(previous: GlassesState, event: GlassesEvent): GlassesTransition {
        var state = previous
        val effects = mutableListOf<GlassesEffect>()
        var nativeSubtitle: NativeSubtitleStart? = null
        fun cancelRestore() {
            if (state.restoreRevision != null) effects += GlassesEffect.CancelRestore
            state = state.copy(restoreRevision = null)
        }
        fun setIntent(intent: DisplayIntent) {
            cancelRestore()
            state = state.copy(intent = intent, revision = state.revision + 1,
                ownership = DisplayOwnership.APP, answerPermit = null, blockedRevision = null, error = null,
                captionMode = intent == DisplayIntent.Translation && state.captionMode,
                mediaCaptionMode = intent == DisplayIntent.Translation && state.mediaCaptionMode,
                translation = if (intent == DisplayIntent.Translation) state.translation else TranslationView())
        }
        fun resetSession() {
            cancelRestore()
            effects += GlassesEffect.ResetPage
            effects += GlassesEffect.StopAudio
            effects += GlassesEffect.CancelCloseTimeout
            state = state.copy(session = null, audio = AudioState())
        }
        fun returnIntent(target: ReturnTarget): DisplayIntent = when (target) {
            ReturnTarget.NAVIGATION -> if (state.navigation.enabled) DisplayIntent.Navigation else DisplayIntent.Idle
            ReturnTarget.PROMPT_STANDBY -> DisplayIntent.PromptStandby
            ReturnTarget.IDLE -> DisplayIntent.Idle
        }
        fun showAnswer(content: AnswerContent) {
            val target = when (val intent = state.intent) {
                DisplayIntent.Navigation -> ReturnTarget.NAVIGATION
                DisplayIntent.PromptStandby, DisplayIntent.BlankPrompt -> ReturnTarget.PROMPT_STANDBY
                is DisplayIntent.Answer -> intent.returnTo
                DisplayIntent.Idle, DisplayIntent.Translation -> ReturnTarget.IDLE
            }
            setIntent(DisplayIntent.Answer(content.ref, target))
            state = state.copy(answer = content)
        }
        when (event) {
            GlassesEvent.BeginConnection -> {
                resetSession()
                setIntent(DisplayIntent.Idle)
                state = state.copy(navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
                state = state.copy(link = LinkState(LinkPhase.CONNECTING, "안경에 연결 중"), disconnecting = false)
            }
            is GlassesEvent.LinkChanged -> {
                val lost = state.link.phase == LinkPhase.FULL && event.state.phase != LinkPhase.FULL
                if (lost) {
                    resetSession()
                    // Navigation is an explicitly selected ongoing feed. A stale answer,
                    // capture page or blank prompt must not reopen on automatic reconnect.
                    val resumeNavigation = state.intent == DisplayIntent.Navigation && state.navigation.enabled
                    state = state.copy(intent = if (resumeNavigation) DisplayIntent.Navigation else DisplayIntent.Idle,
                        captionMode = false, mediaCaptionMode = false, translation = TranslationView(), standby = TranslationView(),
                        revision = state.revision + 1, answerPermit = null, blockedRevision = null, error = null)
                }
                state = state.copy(link = event.state)
                if (state.disconnecting && event.state.phase == LinkPhase.DISCONNECTED) state = state.copy(disconnecting = false)
            }
            is GlassesEvent.NavigationChanged -> {
                state = state.copy(navigation = event.state)
            }
            is GlassesEvent.SelectNavigation -> {
                setIntent(DisplayIntent.Navigation)
                state = state.copy(navigation = event.state)
                effects += GlassesEffect.FollowNavigation(checkNotNull(event.state.key))
            }
            is GlassesEvent.ResumeNavigation -> {
                // A temporary native voice handoff may restore only its exact selected feed.
                if (state.link.phase == LinkPhase.FULL && !state.disconnecting &&
                    state.ownership == DisplayOwnership.APP && state.intent == DisplayIntent.Idle &&
                    state.session == null && state.error == null && state.navigation.enabled &&
                    state.navigation.key == event.key && state.navigation.frame != null)
                    setIntent(DisplayIntent.Navigation)
            }
            GlassesEvent.StopNavigation -> {
                val current = state.intent
                val next = if (current is DisplayIntent.Answer && current.returnTo == ReturnTarget.NAVIGATION)
                    current.copy(returnTo = ReturnTarget.IDLE) else if (current == DisplayIntent.Navigation) DisplayIntent.Idle else current
                val owner = state.ownership
                setIntent(next)
                state = state.copy(ownership = owner, navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
            }
            is GlassesEvent.ForgetAnswer -> {
                if (state.answer?.ref?.source == event.source) state = state.copy(answer = null)
                val intent = state.intent as? DisplayIntent.Answer
                if (intent?.ref?.source == event.source) setIntent(returnIntent(intent.returnTo))
            }
            GlassesEvent.PauseDisplay -> setIntent(DisplayIntent.Idle)
            GlassesEvent.OpenStandby -> {
                setIntent(DisplayIntent.PromptStandby)
                state = state.copy(standby = TranslationView())
            }
            is GlassesEvent.StandbyText -> state = state.copy(standby = event.view)
            GlassesEvent.StartTranslation -> {
                setIntent(DisplayIntent.Translation)
                state = state.copy(captionMode = false, mediaCaptionMode = false, translation = TranslationView())
            }
            is GlassesEvent.StartNativeSubtitle -> {
                if (state.link.phase != LinkPhase.FULL || state.disconnecting || state.session != null ||
                    state.intent != DisplayIntent.Idle || state.navigation.enabled) return GlassesTransition(state, emptyList())
                setIntent(DisplayIntent.Translation)
                state = state.copy(captionMode = event.request.captions, mediaCaptionMode = false, translation = TranslationView())
                nativeSubtitle = event.request
            }
            GlassesEvent.StartCaption -> {
                setIntent(DisplayIntent.Translation)
                state = state.copy(captionMode = true, mediaCaptionMode = false, translation = TranslationView())
            }
            GlassesEvent.StartMediaCaption -> {
                setIntent(DisplayIntent.Translation)
                state = state.copy(captionMode = true, mediaCaptionMode = true, translation = TranslationView())
            }
            is GlassesEvent.TranslationText -> if (state.intent == DisplayIntent.Translation) state = state.copy(translation = event.view)
            GlassesEvent.ClearDisplay -> if (state.session?.mode == PageMode.PROMPT && state.ownership == DisplayOwnership.APP) {
                setIntent(DisplayIntent.BlankPrompt)
            }
            GlassesEvent.RetryAudio -> state.session?.takeIf {
                state.link.phase == LinkPhase.FULL && state.ownership == DisplayOwnership.APP &&
                    !state.disconnecting && it.receivesGlassesAudio && it.phase in setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY)
            }?.let {
                state = state.copy(audio = AudioState(AudioPhase.WAITING_PACKET, "디코더 재시작됨, 새 안경 오디오 패킷 대기"))
                effects += GlassesEffect.StopAudio
                effects += GlassesEffect.StartAudio(it.id, if (it.mode == PageMode.NAVIGATION) 1 else 2)
            }
            GlassesEvent.DismissAnswer -> (state.intent as? DisplayIntent.Answer)?.let { setIntent(returnIntent(it.returnTo)) }
            GlassesEvent.Disconnect -> {
                setIntent(DisplayIntent.Idle)
                state = state.copy(navigation = state.navigation.copy(enabled = false, frame = null))
                effects += GlassesEffect.StopFollowing
                state = state.copy(disconnecting = true)
            }
            is GlassesEvent.AnswerRequested -> state = state.copy(answerPermit = AnswerPermit(event.ref, state.revision))
            is GlassesEvent.CancelAnswer -> if (state.answerPermit?.ref?.source == event.source) state = state.copy(answerPermit = null)
            is GlassesEvent.AnswerCompleted -> {
                if (state.answerPermit == event.permit && event.permit.ref == event.content.ref &&
                    event.permit.revision == state.revision && state.link.phase == LinkPhase.FULL &&
                    state.ownership == DisplayOwnership.APP && !state.disconnecting) showAnswer(event.content)
            }
            is GlassesEvent.RevealAnswer -> if (!state.disconnecting) showAnswer(event.content)
            is GlassesEvent.SessionSignal -> {
                val session = state.session
                if (session?.id != event.id) return GlassesTransition(state, emptyList())
                when (val signal = event.signal) {
                    is PageSignal.Closed -> if (session.phase == PagePhase.CLOSING) {
                        effects += GlassesEffect.CancelCloseTimeout
                        state = state.copy(session = null)
                        if (signal.error != null) state = state.copy(error = signal.error, blockedRevision = state.revision)
                    }
                    is PageSignal.Ended -> {
                        resetSession()
                        setIntent(DisplayIntent.Idle)
                        state = state.copy(ownership = DisplayOwnership.YIELDED, error = signal.message)
                    }
                    else -> if (session.phase != PagePhase.CLOSING) when (signal) {
                        is PageSignal.AudioAccepted -> {
                            if (session.receivesGlassesAudio) {
                                state = state.copy(audio = AudioState(AudioPhase.WAITING_PACKET, "오디오 세션 확인됨, 안경 오디오 대기"))
                                effects += GlassesEffect.StartAudio(session.id, signal.channels)
                            } else state = state.copy(audio = AudioState(detail = "휴대폰 내부 재생음만 사용"))
                        }
                        PageSignal.LayoutPending -> state = state.copy(session = session.copy(phase = PagePhase.LAYOUT))
                        PageSignal.Settling -> state = state.copy(session = session.copy(phase = PagePhase.SETTLING))
                        is PageSignal.Written -> {
                            val latest = signal.content == session.content
                            state = state.copy(session = session.copy(writtenContent = signal.content,
                                phase = if (latest) PagePhase.READY else session.phase))
                            val answer = state.intent as? DisplayIntent.Answer
                            if (latest && answer?.returnTo == ReturnTarget.NAVIGATION && state.navigation.enabled && state.restoreRevision != state.revision) {
                                cancelRestore()
                                state = state.copy(restoreRevision = state.revision)
                                effects += GlassesEffect.ScheduleRestore(session.id, state.revision, answer.ref)
                            }
                        }
                        is PageSignal.Failed -> {
                            cancelRestore()
                            state = state.copy(error = signal.message, blockedRevision = state.revision, answerPermit = null)
                        }
                        else -> Unit
                    }
                }
            }
            is GlassesEvent.AudioChanged -> if (state.session?.id == event.id && state.session?.receivesGlassesAudio == true && state.session?.phase != PagePhase.CLOSING) state = state.copy(audio = event.state)
            is GlassesEvent.RestoreAnswer -> {
                val answer = state.intent as? DisplayIntent.Answer
                if (state.session?.id == event.id && state.session?.phase == PagePhase.READY &&
                    state.revision == event.revision && answer?.ref == event.ref && state.ownership == DisplayOwnership.APP) {
                    setIntent(returnIntent(answer.returnTo))
                }
            }
            is GlassesEvent.CloseTimedOut -> if (state.session?.id == event.id && state.session?.phase == PagePhase.CLOSING) {
                resetSession()
                state = state.copy(error = "페이지 닫기가 완료되지 않았습니다. 다시 시도하세요. 안경의 실제 페이지는 알 수 없습니다", blockedRevision = state.revision)
            }
            is GlassesEvent.ExternalPage -> {
                if (state.ownership == DisplayOwnership.YIELDED && state.session == null && state.intent == DisplayIntent.Idle)
                    return GlassesTransition(state, emptyList())
                resetSession()
                setIntent(DisplayIntent.Idle)
                state = state.copy(ownership = DisplayOwnership.YIELDED, error = event.description)
            }
        }

        val target = target(state)
        val session = state.session
        if (state.link.phase == LinkPhase.FULL && session != null && session.phase != PagePhase.CLOSING &&
            (target == null || target.first != session.mode || session.receivesGlassesAudio == state.mediaCaptionMode || state.blockedRevision == state.revision)) {
            cancelRestore()
            state = state.copy(session = session.copy(phase = PagePhase.CLOSING), audio = AudioState())
            effects += GlassesEffect.StopAudio
            effects += GlassesEffect.ScheduleCloseTimeout(session.id)
            effects += GlassesEffect.ClosePage(session.id)
        } else if (state.link.phase == LinkPhase.FULL && target != null && state.blockedRevision != state.revision) {
            if (session == null) {
                val newSession = PageSession(state.nextSession, target.first, PagePhase.STARTING, target.second, receivesGlassesAudio = !state.mediaCaptionMode, nativeSubtitle = nativeSubtitle)
                state = state.copy(session = newSession, nextSession = state.nextSession + 1)
                effects += GlassesEffect.StartPage(newSession)
            } else if (session.phase != PagePhase.CLOSING && session.content != target.second) {
                state = state.copy(session = session.copy(content = target.second))
                effects += GlassesEffect.UpdatePage(session.id, target.second)
            }
        }
        if (state.disconnecting && state.session == null && state.link.phase != LinkPhase.CLOSING) {
            state = state.copy(link = LinkState(LinkPhase.CLOSING, "안경 연결 해제 중"))
            effects += GlassesEffect.DisconnectTransport
        }
        return GlassesTransition(state, effects)
    }

    private fun target(state: GlassesState): Pair<PageMode, PageContent>? {
        if (state.disconnecting || state.ownership != DisplayOwnership.APP) return null
        return when (val intent = state.intent) {
            DisplayIntent.Idle -> null
            DisplayIntent.BlankPrompt -> PageMode.PROMPT to PageContent("blank", "　", "　")
            DisplayIntent.PromptStandby -> PageMode.PROMPT to PageContent("standby",
                state.standby.source.ifBlank { "OpenRayneo" }, state.standby.target.ifBlank { "실시간 힌트 준비됨" })
            DisplayIntent.Translation -> if (state.mediaCaptionMode)
                PageMode.PROMPT to PageContent("media-caption", state.translation.source.ifBlank { "미디어 원문 자막" },
                    state.translation.target.ifBlank { "휴대폰 재생음을 기다립니다" })
            else if (state.captionMode)
                PageMode.PROMPT to PageContent("caption", state.translation.source.ifBlank { "실시간 자막" },
                    state.translation.target.ifBlank { "말하면 자막이 표시됩니다" })
            else PageMode.PROMPT to PageContent("translation",
                state.translation.source.ifBlank { "실시간 번역 · 한국어 ↔ 영어" }, state.translation.target.ifBlank { "말하면 번역됩니다" })
            DisplayIntent.Navigation -> state.navigation.frame?.takeIf { state.navigation.enabled }?.let {
                PageMode.NAVIGATION to PageContent("navigation", it)
            }
            is DisplayIntent.Answer -> state.answer?.takeIf { it.ref == intent.ref }?.let {
                PageMode.PROMPT to PageContent("${it.ref.source}:${it.ref.taskId}", it.question.ifBlank { "Agent" }, it.text)
            }
        }
    }
}
