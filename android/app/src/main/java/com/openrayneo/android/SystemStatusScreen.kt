// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openrayneo.android.SystemStatusPolicy.Phase
import com.openrayneo.android.SystemStatusPolicy.Status

private data class SystemRow(val group: String, val title: String, val status: Status,
                             val detail: String, val route: String, val permitted: Boolean? = null, val needsAction: Boolean = false)
private fun status(phase: Phase, label: String) = Status(phase, label)
private fun SystemRow.attention() = needsAction || status.phase == Phase.ERROR
private fun SystemRow.priority() = when {
    attention() -> 0
    working() -> 1
    status.phase == Phase.WAITING -> 2
    status.phase == Phase.READY -> 3
    else -> 4
}
private fun SystemRow.working() = status.phase in setOf(Phase.ACTIVE, Phase.BUSY)
private fun connection(s: CompanionState) = if (!s.available) status(Phase.UNKNOWN, "서비스 대기")
    else SystemStatusPolicy.link(s.glasses.link.phase.name, s.glasses.link.reconnectWaiting, s.glasses.disconnecting)
internal fun recording(s: CompanionState) = if (!s.available) status(Phase.UNKNOWN, "미확인") else with(s.conversation) {
    SystemStatusPolicy.capture(running, stopping, reprocessing, liveTranscriptOnly, s.alwaysOnResumePending,
        s.audioLevels.coordinatorState().phase.name, mode == ConversationMode.ENROLL)
}
private fun receiver(s: CompanionState) = if (!s.available) status(Phase.UNKNOWN, "미확인")
    else SystemStatusPolicy.receiver(s.agentServer.enabled, s.agentServer.pollRecent)
private fun rows(model: AppModel, phoneInput: String?): List<SystemRow> {
    val s = model.state; val c = s.conversation; val cfg = model.config
    val linked = s.available && s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val audio = s.audioLevels.coordinatorState()
    val rec = recording(s)
    val live = c.running && !c.reprocessing && !c.stopping
    val ai = s.agentServer.enabled && s.agentServer.pollRecent
    val mic = if (linked && s.deviceSettings.loaded) s.deviceSettings.settings.optJSONObject("privacyConfig")?.deviceFlag("mic_switch") else null
    val firmware = if (linked && s.deviceSettings.loaded) s.deviceSettings.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("voiceWakeup") else null
    val speech = c.modelReady && model.speechConfigured
    val ambient = cfg.optBoolean("ambientWakeEnabled")
    val fast = cfg.optBoolean("fastRealtimeHints")
    val native = cfg.optBoolean("nativeAutoAnswer")
    val hearing = live && (c.source == ConversationSource.ALWAYS_ON || c.source == ConversationSource.REALTIME && fast)
    val updates = model.updateCheck
    val off = status(Phase.OFF, "꺼짐")
    val unknown = status(Phase.UNKNOWN, "미확인")
    fun active(on: Boolean) = SystemStatusPolicy.activity(on)
    fun allowed(on: Boolean, ready: Boolean, running: Boolean = false, blocked: Boolean = false): Status = when {
        !on -> off
        blocked -> status(Phase.BLOCKED, "일시 제한")
        running -> status(Phase.ACTIVE, "작동 중")
        !ready -> status(Phase.WAITING, "조건 대기")
        else -> status(Phase.READY, "켜짐")
    }
    return buildList {
        fun addRow(g: String, title: String, st: Status, detail: String, route: String, permit: Boolean? = null, needsAction: Boolean = false) {
            // Service-backed rows must never claim off/ready from a default disconnected snapshot.
            add(SystemRow(g, title, if (!s.available && g != "유지관리" && title != "휴대폰 마이크") unknown else st,
                if (!s.available && g != "유지관리" && title != "휴대폰 마이크") "서비스 상태 확인 대기" else detail, route, permit, s.available && needsAction))
        }
        addRow("기기", "안경 연결", connection(s), if (linked) {
            val battery = if (s.deviceSettings.loaded) s.deviceSettings.status.deviceInt("battery")?.takeIf { it in 0..100 } else null
            if (battery != null) "완전 연결 · 최근 배터리 $battery%" else "완전 연결 · 배터리 미확인"
        } else when {
            !model.bluetoothAllowed -> "근처 기기 권한 필요"
            s.glasses.link.phase == LinkPhase.BLE_ONLY -> "SPP 연결 미완료 · 음성 및 HUD 사용 불가"
            s.glasses.link.reconnectWaiting -> "동일 안경 자동 재연결 대기"
            else -> "블루투스 연결 상태"
        }, "device", needsAction = !model.bluetoothAllowed || s.glasses.link.phase == LinkPhase.BLE_ONLY)
        addRow("기기", "안경 마이크 설정", when (mic) { true -> status(Phase.READY,"켜짐"); false -> off; null -> unknown }, "기기 보고값 · 실제 음성 수신과 별도", "device-settings", mic)
        addRow("기기", "안경 화면", when {
            !linked -> unknown
            s.glasses.error != null -> status(Phase.ERROR,"오류")
            s.glasses.ownership == DisplayOwnership.YIELDED -> status(Phase.BLOCKED,"표시 양보")
            s.glasses.session?.phase == PagePhase.READY -> status(Phase.READY,"세션 준비")
            s.glasses.session != null -> status(Phase.BUSY,"전환 중")
            else -> status(Phase.READY,"유휴")
        }, "휴대폰의 전송 세션 기준 · 실표시는 별도", "display-controls")
        addRow("음성·기록", "안경 음성 수신", when(audio.phase) {
            AudioPhase.RECEIVING -> status(Phase.ACTIVE,"수신 중")
            AudioPhase.WAITING_PACKET, AudioPhase.WAITING_PCM -> status(Phase.WAITING,"수신 대기")
            AudioPhase.STALLED -> status(Phase.BLOCKED,"수신 중단")
            AudioPhase.FAILED -> status(Phase.ERROR,"오류")
            AudioPhase.STOPPED -> off
        }, when(audio.phase) {
            AudioPhase.WAITING_PACKET -> "첫 오디오 패킷 대기"
            AudioPhase.WAITING_PCM -> "패킷 수신됨 · PCM 디코딩 대기"
            AudioPhase.STALLED -> "최근 3초간 PCM 미수신"
            AudioPhase.FAILED -> "오디오 디코더 오류 · 진단에서 확인"
            else -> "최근 PCM 기준 · 실제 발화 여부와 별도"
        }, "diagnostics", needsAction = audio.phase == AudioPhase.STALLED)
        addRow("음성·기록", "휴대폰 마이크", if(phoneInput == null) off else status(if(phoneInput == "마이크 입력 중") Phase.ACTIVE else Phase.BUSY, phoneInput), "직접 음성 질문 입력 · 자동 전송 없음", "ai")
        addRow("음성·기록", "기록", rec, when {
            c.reprocessing -> "저장된 원본 재처리 · 새 녹음 아님"
            live -> c.mode.title + " · " + if(c.liveTranscriptOnly) "원본 미보존" else if(c.mode == ConversationMode.ENROLL) "목소리 등록" else "원본 보존 설정"
            else -> "직접 시작한 기록만 실행"
        }, "records")
        addRow("음성·기록", "음성 인식 설정", if(model.speechConfigured) status(Phase.READY,"설정됨") else status(Phase.BLOCKED,"설정 필요"), "모델 확보 또는 외부 설정 · 인식 성공과 별도", "voice-settings", needsAction = !model.speechConfigured)
        addRow("음성·기록", "기록·화자 엔진", if(c.modelReady) status(Phase.READY,"초기화 완료") else if(c.error != null) status(Phase.ERROR,"초기화 실패") else status(Phase.WAITING,"초기화 대기"), "실제 화자 식별 품질은 별도", "voice-profiles")
        addRow("음성·기록", "기록 요약", if(c.summarizing) status(Phase.ACTIVE,"요약 중") else off, "저장된 기록 처리", "records")
        addRow("음성·기록", "음성 출력", active(s.speechPlaying), "휴대폰 오디오 경로", "voice-settings")
        addRow("AI·기억", "AI 연결", receiver(s), if(s.agentServer.enabled && !ai) "외부 Agent 요청 수신 대기 · 실행 환경 확인" else "최근 요청 수신 기준 · 모델 응답 별도", "agent-api", s.agentServer.enabled, s.agentServer.enabled && !ai)
        addRow("AI·기억", "AI 질문·업무", active(s.phoneRunning || s.voiceAiPending), if(s.voiceAiPending) "안경 호출 질문 응답 대기" else "휴대폰 AI 처리", "ai")
        addRow("AI·기억", "기본 AI 호출", allowed(native, linked && speech && ai && mic == true && firmware == true,
            live && c.source == ConversationSource.NATIVE && ai && speech, mic == false || firmware == false), "안경 기본 호출 이벤트", "korean-wake", native)
        addRow("AI·기억", "한국어 호출", when {
            s.wakePending -> status(Phase.BUSY,"준비 중")
            s.wakeActive -> status(Phase.ACTIVE,"호출 듣는 중")
            else -> allowed(ambient, linked && speech && ai && hearing, false, mic == false)
        }, "명시 호출 또는 기록 중 감지", "korean-wake", ambient)
        addRow("AI·기억", "실시간 힌트", allowed(fast, linked && speech && ai && live && c.source == ConversationSource.REALTIME && audio.phase == AudioPhase.RECEIVING,
            false, s.focusMode || !s.appNotificationsEnabled || mic == false), "활성 설정 · 실제 힌트 생성은 별도", "feature-controls", fast)
        addRow("AI·기억", "기억 추천", allowed(cfg.optBoolean("memorySuggestOnSend"), !s.migration.busy, blocked = s.migration.busy), "질문 작성 시 기기 내 검색", "feature-controls", cfg.optBoolean("memorySuggestOnSend"))
        addRow("AI·기억", "대화 이어가기", if(s.chatContinuityError.isNotBlank()) status(Phase.ERROR,"오류") else allowed(s.chatContinuity.enabled, ai, s.chatContinuityBusy), "일반 대화 맥락 저장", "ai", s.chatContinuity.enabled)
        addRow("업무", "PC 연결", if(s.computerConnected) status(Phase.READY,"연결됨") else status(Phase.OFF,"미연결"), "Codex 연결", "computer")
        addRow("업무", "PC 작업", active(s.computerRunning), "PC 작업 처리", "ai")
        val pending = s.approvals.size + s.agentReviews.size
        addRow("업무", "작업 승인", if(pending > 0) status(Phase.WAITING,"${pending}건 대기") else off, "명시적 확인 필요", "ai", needsAction = pending > 0)
        addRow("번역·도구", "주변 통역", active(s.translationActive && !s.captionActive && !s.mediaCaption.busy && !s.wakeActive && !s.wakePending), "통역 세션 · HUD 실표시는 별도", "translation")
        addRow("번역·도구", "주변 자막", active(s.captionActive), "안경 음성 자막 세션", "captions")
        addRow("번역·도구", "영상 자막", when(s.mediaCaption.phase) {
            MediaCapturePhase.IDLE -> off
            MediaCapturePhase.STARTING -> status(Phase.BUSY,"준비 중")
            MediaCapturePhase.CAPTURING -> status(Phase.ACTIVE,"수집 중")
            MediaCapturePhase.STOPPING -> status(Phase.BUSY,"종료 중")
            MediaCapturePhase.ERROR -> status(Phase.ERROR,"오류")
        }, "휴대폰 재생음 · 안경 마이크와 별도", "media-captions")
        val selectedNav = s.navigationEntries.firstOrNull { it.key == s.glasses.navigation.key }
        addRow("번역·도구", "길 안내", when {
            !s.glasses.navigation.enabled -> off
            !model.notificationAllowed || !s.notificationConnected || !linked -> status(Phase.BLOCKED, "연결 대기")
            selectedNav?.safeInstruction == null -> status(Phase.WAITING,"최신 안내 대기")
            s.glasses.session?.mode == PageMode.NAVIGATION && s.glasses.session?.phase == PagePhase.READY -> status(Phase.ACTIVE,"안내 전송")
            else -> status(Phase.BUSY,"화면 준비")
        }, if(s.glasses.navigation.enabled) s.navigationStatus else "지도 앱 알림 추종 · 경로 계산은 지도 앱", "navigation",
            needsAction = s.glasses.navigation.enabled && (!model.notificationAllowed || !s.notificationConnected))
        addRow("번역·도구", "프롬프터", when {
            s.teleprompter.busy -> status(Phase.BUSY,"전송 중")
            s.teleprompter.active != null && s.teleprompter.paused -> status(Phase.BLOCKED,"일시정지")
            s.teleprompter.active != null -> status(Phase.ACTIVE,"세션 실행")
            else -> off
        }, "원고 전송 상태", "teleprompter")
        addRow("번역·도구", "타이머", active(s.timerRunning), "휴대폰 타이머", "local-clock")
        addRow("번역·도구", "스톱워치", active(s.stopwatchRunning), "휴대폰 시간 측정", "local-clock")
        addRow("자동 기능", "집중 모드", allowed(s.focusMode,true), "자동 알림·힌트 제한", "feature-controls", s.focusMode)
        addRow("자동 기능", "휴대폰 알림", allowed(s.appNotificationsEnabled, linked && model.notificationAllowed && s.notificationConnected, blocked = s.focusMode), when {
            s.focusMode -> "집중 모드에서 전달 제한"
            !model.notificationAllowed -> "알림 접근 권한 필요"
            !linked -> "안경 연결 대기"
            !s.notificationConnected -> "알림 리스너 연결 대기"
            else -> "허용 앱 알림 전달 대기"
        }, "phone-notifications", s.appNotificationsEnabled, s.appNotificationsEnabled && !s.focusMode && linked && (!model.notificationAllowed || s.notificationReadPhase == NotificationReadPhase.FAILED))
        addRow("자동 기능", "백그라운드 서비스", active(s.foregroundService), "Android 지속 알림 서비스", "device")
        addRow("자동 기능", "기록 동기화", allowed(s.recordSync.enabled,true), "연결 서버 활성 여부 · 전송 성공 별도", "agent-api", s.recordSync.enabled)
        addRow("유지관리", "번역 모델 준비", if(s.mediaTranslation.preparing || s.offlinePreparing) status(Phase.BUSY,"준비 중") else off, "필요할 때 내장 모델 준비", if(s.mediaTranslation.language == MediaTranslationRoute.BILINGUAL || s.offlinePreparing) "translation" else "media-captions")
        addRow("유지관리", "기록 이전", if(!s.available) unknown else if(s.migration.busy) status(Phase.BUSY,"이전 중") else off, "기존 앱 기록 가져오기", "record-migration")
        if(AppUpdate.supported) addRow("유지관리", "앱 업데이트", when {
            AppUpdateInstall.sessionId != null -> status(Phase.BUSY,"설치 준비")
            updates.downloading -> status(Phase.BUSY, "다운로드·검증")
            updates.checking -> status(Phase.BUSY,"확인 중")
            updates.error.isNotBlank() -> status(Phase.ERROR,"확인 실패")
            updates.candidate != null -> status(Phase.READY,"새 버전 있음")
            else -> status(Phase.READY,"대기")
        }, if(updates.downloading && updates.downloadTotal > 0) "전송 ${(updates.downloadProgress * 100 / updates.downloadTotal).coerceIn(0,100)}% · 복원 별도" else "서명 검증 후 사용자 승인", "app-update")
    }
}
@Composable private fun statusColor(phase: Phase): Color = when(phase) {
    Phase.ERROR -> MaterialTheme.colorScheme.error
    Phase.BLOCKED -> MaterialTheme.colorScheme.tertiary
    Phase.ACTIVE, Phase.READY -> MaterialTheme.colorScheme.primary
    Phase.BUSY -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
@Composable internal fun SystemStatusStrip(model: AppModel, phoneInput: String?, open: () -> Unit) {
    val s = model.state
    val rec = recording(s)
    val capture = when {
        phoneInput != null -> phoneInput
        !s.available -> "기록 미확인"
        s.mediaCaption.busy -> "영상 자막"
        s.wakeActive || s.wakePending -> "호출 대기"
        s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending -> rec.label
        s.captionActive -> "주변 자막"
        s.translationActive -> "통역 세션"
        s.audioLevels.active -> "음성 수신 세션"
        else -> "기록 꺼짐"
    }
    Surface(onClick = open, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(connection(s).label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = statusColor(connection(s).phase), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(capture, Modifier.weight(1.35f), style = MaterialTheme.typography.labelMedium,
                color = if (capture == "녹음 중" || capture == "마이크 입력 중") MaterialTheme.colorScheme.error else statusColor(rec.phase),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("AI " + receiver(s).label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = statusColor(receiver(s).phase), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pending = s.approvals.size + s.agentReviews.size
            if (pending > 0) Text("승인 $pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Icon(Icons.Outlined.ChevronRight, "시스템 현황 열기")
        }
    }
}
@Composable internal fun SystemStatusScreen(model: AppModel, phoneInput: String?, navigate: (Int)->Unit, open: (String)->Unit, connect: ()->Unit) {
    var filter by rememberSaveable { mutableStateOf("전체") }
    val data = rows(model, phoneInput)
    val active = data.count { it.working() }; val attention = data.count { it.attention() }
    val visible = data.filter { filter == "전체" || filter == "작동 중" && it.working() || filter == "확인 필요" && it.attention() }
    val ordered = visible.sortedBy { it.priority() }
    val urgent = ordered.filter { it.attention() || it.working() }
    val remaining = ordered.filterNot { it.attention() || it.working() }
    val sections = buildList<Pair<String,List<SystemRow>>> {
        if(urgent.isNotEmpty()) add("현재 활동 · 확인 필요" to urgent)
        remaining.groupBy { it.group }.forEach { (group, entries) -> add(group to entries) }
    }
    fun go(route: String) { when(route) {
        "device" -> if(model.state.glasses.link.phase == LinkPhase.DISCONNECTED) connect() else navigate(4)
        "ai" -> navigate(1)
        "records" -> navigate(2)
        else -> open(route)
    } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ContentCard {
                Text("현재 시스템 상태", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("작동·처리 $active", color = MaterialTheme.colorScheme.primary)
                    Text("확인 필요 $attention", color = if(attention > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Note("켜짐은 사용 허용 또는 연결 설정, 작동 중은 관측된 세션입니다. 꺼짐은 오류가 아닙니다.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("전체","작동 중","확인 필요").forEach { FilterChip(filter == it, { filter = it }, label = { Text(it) }) }
                }
                val s = model.state
                if(s.conversation.running || s.alwaysOnResumePending)
                    ToolOutlinedButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !s.conversation.stopping) { Text(if(s.conversation.reprocessing) "재전사 중지" else "기록 중지") }
                if(s.mediaCaption.busy) ToolOutlinedButton(onClick = { model.attempt { model.service().stopMediaCaption() } }, enabled = s.mediaCaption.phase != MediaCapturePhase.STOPPING) { Text("영상 자막 종료") }
                if(s.wakeActive || s.wakePending) ToolOutlinedButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }) { Text("호출 중지") }
                else if(s.captionActive || s.translationActive && !s.mediaCaption.busy) ToolOutlinedButton(onClick = { model.attempt { model.service().stopTranslation() } }) { Text("통역·자막 종료") }
                if(s.speechPlaying) ToolOutlinedButton(onClick = { model.attempt { model.service().speechOutput.stop() } }) { Text("음성 출력 중지") }
            }
        }
        if(visible.isEmpty()) item { Note("해당 상태의 항목이 없습니다.") }
        sections.forEach { (group, entries) ->
            item(key = "group:"+group) { SectionLabel(group) }
            items(entries, key = { it.title }) { row ->
                OutlinedCard(onClick = { go(row.route) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(row.title) }, supportingContent = {
                        Column {
                            Text(row.status.label, color = statusColor(row.status.phase), style = MaterialTheme.typography.labelLarge)
                            Text(row.detail)
                            row.permitted?.let { Text(if(it) "허용 설정 켜짐" else "허용 설정 꺼짐", style = MaterialTheme.typography.labelSmall) }
                        }
                    }, trailingContent = { Icon(Icons.Outlined.ChevronRight,"관리") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
        item { ToolOutlinedButton(onClick = { open("feature-controls") }, modifier = Modifier.fillMaxWidth()) { Text("자동 기능 허용 설정 관리") } }
    }
}
