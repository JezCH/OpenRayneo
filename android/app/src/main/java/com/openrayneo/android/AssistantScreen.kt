// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(model: AppModel, actions: UiActions, configure: (String) -> Unit) {
    var mobile by rememberSaveable { mutableStateOf(true) }
    var executeTask by remember { mutableStateOf(false) }
    var memoryQuestion by remember { mutableStateOf(false) }
    var autoMemory by remember { mutableStateOf(false) }
    var continuityDialog by remember { mutableStateOf(false) }
    var confirmNewChat by remember { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showContextOptions by rememberSaveable { mutableStateOf(false) }
    val suggestMemory = model.config.optBoolean("memorySuggestOnSend")
    val source = if (mobile) AnswerSource.MOBILE_AGENT else AnswerSource.COMPUTER_CODEX
    val s = model.state
    val running = if (mobile) s.phoneRunning else s.computerRunning
    val question = if (mobile) s.phoneQuestion else s.computerQuestion
    val answer = if (mobile) s.phoneAnswer else s.computerAnswer
    val status = if (mobile) s.phoneStatus else s.computerStatus
    val history = if (mobile) model.phoneHistory else model.computerHistory
    val configured =
        if (mobile)
            s.agentServer.enabled
        else listOf("codexUrl", "codexToken").all { model.config.optString(it).isNotBlank() }
    val draft = if (mobile) model.phoneDraft else model.computerDraft
    val inputReady = s.available && configured && (if (mobile) s.agentServer.pollRecent else s.computerConnected) && !running
    // Search locally after a pause in typing; cancellation prevents stale recommendations.
    var suggestionCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(draft, mobile, executeTask, suggestMemory, running, s.available, s.migration.busy,
        model.config.optString("memorySuggestionDays"), model.config.optString("memorySuggestionLimit")) {
        suggestionCount = 0
        if (mobile && !executeTask && suggestMemory && !running && s.available && !s.migration.busy && draft.trim().length >= 4 && draft.length <= 2000) {
            delay(800)
            suggestionCount = suspendCancellableCoroutine<Int> { continuation ->
                try {
                    model.service().suggestQuestionMemory(draft) { result ->
                        if (continuation.isActive) continuation.resume(result.getOrNull()?.hits?.size ?: 0)
                    }
                } catch (_: Exception) { if (continuation.isActive) continuation.resume(0) }
            }
        }
    }
    val phoneList = rememberLazyListState()
    val computerList = rememberLazyListState()
    val list = if (mobile) phoneList else computerList
    var followLatest by remember(source) { mutableStateOf(true) }
    LaunchedEffect(list, source) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, forward) -> if (scrolling) followLatest = !forward }
    }
    LaunchedEffect(source, answer, question, s.approvals.size, s.agentReviews.size) {
        withFrameNanos {}
        if (followLatest && list.layoutInfo.totalItemsCount > 0)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    if (memoryQuestion) MemoryQuestionDialog(model, model.phoneDraft, autoMemory) { memoryQuestion = false; autoMemory = false }
    if (continuityDialog) AlertDialog(
        onDismissRequest = { continuityDialog = false },
        title = { Text("휴대폰·안경 대화 이어가기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("켜면 이후 성공한 일반 AI 질문과 답변을 최대 30턴까지 휴대폰에 암호화 저장합니다. 앱 재시작 후에도 두 기기가 공유하며, 질문마다 최근 3턴과 관련 과거 1턴(없으면 직전 1턴)을 설정된 AI로 전송할 수 있습니다.")
                Note("업무 실행·주변 녹음·별도 기억 첨부 질문은 저장하지 않습니다. 안경 호출과 화자 구분은 본인 인증이 아닙니다. 민감한 질문은 공유하지 마세요. 새 대화 또는 공유 해제 시 이 저장 맥락을 초기화합니다. 기존 녹음 기록에는 영향이 없습니다.")
                if (s.chatContinuityError.isNotBlank()) Text(s.chatContinuityError, color = MaterialTheme.colorScheme.error)
                if (s.chatContinuity.turns.isNotEmpty()) {
                    Text("저장된 맥락 ${s.chatContinuity.turns.size}턴", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.chatContinuity.turns.asReversed().forEach { turn ->
                            item {
                                Text(if (turn.source == "phone") "휴대폰" else "안경", style = MaterialTheme.typography.labelSmall)
                                Text(turn.question)
                                Text(turn.answer, style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = s.available && !s.chatContinuityBusy, onClick = {
                model.attempt { model.service().configureChatContinuity(!s.chatContinuity.enabled); continuityDialog = false }
            }) { Text(if (s.chatContinuity.enabled) "공유 끄고 저장 맥락 초기화" else "동의하고 이어가기 켜기") }
        },
        dismissButton = { TextButton(onClick = { continuityDialog = false }) { Text("닫기") } }
    )
    if (confirmNewChat) AlertDialog(
        onDismissRequest = { confirmNewChat = false },
        title = { Text("새 대화 시작") },
        text = { Text("현재 저장된 일반 대화 맥락을 초기화합니다. 녹음 기록과 별도 개인화 정보는 유지됩니다.") },
        confirmButton = { TextButton(onClick = { model.newConversation(AnswerSource.MOBILE_AGENT); confirmNewChat = false }) { Text("새 대화 시작") } },
        dismissButton = { TextButton(onClick = { confirmNewChat = false }) { Text("취소") } }
    )
    Column(Modifier.fillMaxSize().imePadding()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SegmentedButton(
                mobile,
                { mobile = true },
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 8.dp,
                    bottomStart = 8.dp,
                ),
            ) {
                Text(if (s.phoneRunning) "AI · 처리 중" else "AI 비서")
            }
            SegmentedButton(
                !mobile,
                { mobile = false },
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topEnd = 8.dp,
                    bottomEnd = 8.dp,
                ),
            ) {
                Text(
                    if (s.approvals.isNotEmpty()) "컴퓨터 · 응답 대기"
                    else if (s.computerRunning) "컴퓨터 · 처리 중" else "PC 작업"
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (mobile && configured && question.isBlank() && !running) s.agentServer.receiverStatus
                else status,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            IconButton(
                onClick = { if (mobile && s.chatContinuity.turns.isNotEmpty()) confirmNewChat = true else model.newConversation(source) },
                enabled = s.available && !running && (mobile || configured),
            ) {
                Icon(Icons.Outlined.AddComment, "새 대화")
            }
        }
        if (mobile && s.approvals.isNotEmpty()) TextButton(onClick = { mobile = false }) { Text("PC 작업 승인 ${s.approvals.size}건 확인") }
        if (!mobile && s.agentReviews.isNotEmpty()) TextButton(onClick = { mobile = true }) { Text("AI 작업 승인 ${s.agentReviews.size}건 확인") }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!configured)
                item {
                    ContentCard {
                        Icon(
                            if (mobile) Icons.Outlined.AutoAwesome else Icons.Outlined.Computer,
                            null,
                            Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (mobile) "AI에 연결해야 질문할 수 있습니다" else "컴퓨터가 아직 설정되지 않음",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Note(
                            if (mobile) "AI 연결 설정에서 연결을 켠 뒤 DSH Go 플러그인을 연결하세요. 일반 질문과 업무 실행은 이 연결을 사용합니다."
                            else "컴퓨터의 Codex에 연결해 작업 진행 상황을 보고, 여기서 승인하고 답변을 받습니다."
                        )
                        ToolButton(onClick = { configure(if (mobile) "agent-api" else "computer") }) {
                            Text(if (mobile) "AI 연결 설정" else "컴퓨터 연결 설정")
                        }
                    }
                }
            else if (mobile && !s.agentServer.pollRecent && !running)
                item {
                    ContentCard {
                        Text("AI 연결 대기 중", style = MaterialTheme.typography.titleMedium)
                        Note(s.agentServer.receiverStatus)
                        Note("연결 기능은 켜져 있지만 외부 AI가 아직 요청을 수신하지 않습니다. 질문 초안은 유지됩니다.")
                        ToolButton(onClick = { configure("agent-api") }) { Text("AI 연결 확인") }
                    }
                }
            else if (!mobile && !s.computerConnected)
                item {
                    ContentCard {
                        Text("컴퓨터 Codex 연결", style = MaterialTheme.typography.titleLarge)
                        Note("컴퓨터 연결 프로그램이 실행 중인지 확인하세요. USB 모드는 데이터 케이블과 포트 포워딩이 필요합니다.")
                        ToolButton(
                            onClick = { model.attempt { model.service().connectCodex() } },
                            enabled = s.available,
                        ) {
                            Text("연결／세션 복구")
                        }
                        TextButton(onClick = { configure("computer") }) { Text("연결 설정 확인") }
                    }
                }
            else if (question.isBlank() && history.isEmpty())
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("새 대화", style = MaterialTheme.typography.headlineSmall)
                        Note(if (mobile) "이렇게 물어보세요: 지금 안경의 내비게이션 추종 상태는 어때?" else "작업을 입력하면 컴퓨터의 Codex가 처리합니다.")
                    }
                }
            s.agentReviews.forEach { review ->
                item(key = "agent-review:${review.id}") { AgentWriteReviewCard(model, review) }
            }
            if (s.agentReceipts.isNotEmpty()) item(key = "agent-receipts") {
                AgentReceiptCard(model, s.agentReceipts)
            }
            if (configured) item(key = "assistant-controls") {
                Column {
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "AI 연결 진단·무음 조작 접기" else "AI 연결 진단·무음 조작 보기")
                    }
                    if (showDiagnostics) {
                        if (mobile) AgentProbeCard(model)
                        HudRemoteCard(model, source)
                    }
                }
            }
            history.forEach { record -> item { ConversationTurn(record.question, record.answer) } }
            if (question.isNotBlank())
                item {
                    ConversationTurn(question, answer)
                    if (running) {
                        Row(
                            Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Note(if (!mobile && s.approvals.isNotEmpty()) "응답을 기다리는 중" else "처리 중…")
                        }
                    } else if (answer.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                model.attempt { model.service().speakKorean(answer) }
                            }, enabled = !s.speechPlaying && !s.conversation.running && !s.translationActive && !s.nativeWakeAvailable && !s.teleprompter.smartFollow) { Text("한국어로 듣기") }
                            if (s.speechPlaying) TextButton(onClick = { model.attempt { model.service().speechOutput.stop() } }) { Text("음성 중지") }
                        }
                        Note(s.speechStatus + " · 기본은 이어폰 경로 확인 후 재생합니다. 무음이 필요한 장소에서는 HUD만 사용하세요.")
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolOutlinedButton(
                                onClick = {
                                    model.attempt { model.service().revealAnswer(source) }
                                },
                                enabled = s.glasses.link.phase == LinkPhase.FULL,
                            ) {
                                Text("안경에 표시")
                            }
                            val context = LocalContext.current
                            TextButton(
                                onClick = {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("답변", answer))
                                    model.notice = "답변이 복사됨"
                                }
                            ) {
                                Text("복사")
                            }
                        }
                    }
                }
            if (!mobile)
                s.approvals.forEach { request ->
                    item(key = "approval:${request.id}") { ApprovalCard(model, request) }
                }
            if ((s.glasses.intent as? DisplayIntent.Answer)?.ref?.source == source)
                item {
                    ContentCard {
                        Text("안경 출력", style = MaterialTheme.typography.titleSmall)
                        GlassesOutputPreview(s.glasses)
                        if (
                            (s.glasses.intent as? DisplayIntent.Answer)?.returnTo ==
                                ReturnTarget.NAVIGATION
                        )
                            Note("답변 표시 약 12초 후 내비게이션으로 돌아갑니다. 지금 답변을 종료할 수도 있습니다.")
                        TextButton(
                            onClick = { model.attempt { model.service().dismissAnswer() } }
                        ) {
                            Text("안경 답변 종료")
                        }
                    }
                }
            item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (mobile) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !executeTask, onClick = { executeTask = false }, label = { Text("일반 대화") }, enabled = !running)
                        FilterChip(selected = executeTask, onClick = { executeTask = true }, label = { Text("업무 실행") }, enabled = !running)
                    }
                    if (executeTask) Note("외부 도구 사용 · 쓰기 작업은 승인 필요")
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { autoMemory = suggestMemory && draft.isNotBlank(); memoryQuestion = true },
                                enabled = !running && !s.migration.busy && draft.length <= 2000, modifier = Modifier.weight(1f)) {
                                Text(if (suggestionCount > 0) "관련 기억 ${suggestionCount}개" else "기억 첨부")
                            }
                            TextButton(onClick = { showContextOptions = !showContextOptions }, modifier = Modifier.weight(1f)) {
                                Text(if (showContextOptions) "맥락 설정 접기" else "맥락 설정")
                            }
                        }
                        if (showContextOptions) {
                            TextButton(onClick = { continuityDialog = true }, enabled = s.available) {
                                Text(if (s.chatContinuity.enabled) "대화 이어가기 켜짐 · ${s.chatContinuity.turns.size}턴" else "대화 이어가기 꺼짐 · 설정")
                            }
                            OrdinaryContextButton()
                        }
                    }
                    if (s.chatContinuityError.isNotBlank()) Note(s.chatContinuityError)
                }
                if (!inputReady && !running) {
                    Note(when {
                        !s.available -> "연결 서비스 준비 중"
                        !configured -> if (mobile) "AI 연결 설정을 먼저 완료하세요." else "컴퓨터 연결 설정을 먼저 완료하세요."
                        mobile && !s.agentServer.pollRecent -> "외부 Agent의 최근 수신 요청이 없습니다. DSH Go 연결 또는 처리 상태를 확인하세요. 입력 내용은 유지됩니다."
                        !mobile && !s.computerConnected -> "컴퓨터 연결이 끊겼습니다."
                        else -> "현재 질문을 보낼 수 없습니다."
                    })
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        draft,
                        { if (mobile) model.phoneDraft = it else model.computerDraft = it },
                        Modifier.weight(1f),
                        placeholder = { Text(if (mobile) (if (executeTask) "실행할 업무를 입력하세요…" else "AI에게 질문하세요…") else "컴퓨터에 맡길 작업…") },
                        maxLines = 5,
                        shape = MaterialTheme.shapes.large,
                    )
                    if (running)
                        FilledIconButton(
                            onClick = { model.attempt { model.service().cancelAnswer(source) } },
                            Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Stop, "현재 작업 중지")
                        }
                    else
                        FilledIconButton(
                            onClick = {
                                followLatest = true
                                model.send(source, executeTask = mobile && executeTask)
                                executeTask = false
                            },
                            enabled = inputReady && draft.isNotBlank(),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                "${if (mobile) "로컬 Agent" else "컴퓨터 Codex"}(으)로 보내기",
                            )
                        }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { actions.voice(source) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(Icons.Outlined.MicNone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("휴대폰 음성 입력", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (mobile) (if (executeTask) "업무 Agent로 보내기" else "도구 없는 AI 대화") else "컴퓨터 Codex로 보내기",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HudRemoteCard(model: AppModel, source: AnswerSource) {
    val requester = remember(source) { FocusRequester() }
    var armed by remember(source) { mutableStateOf(false) }
    var focused by remember(source) { mutableStateOf(false) }
    ContentCard {
        Text("무음 HUD 키 조작", style = MaterialTheme.typography.titleMedium)
        Note("이 화면의 조작 영역에 포커스가 있는 동안만 외부 키보드·HID 입력을 받습니다. Enter/가운데 버튼: 최근 답변 표시, Esc/왼쪽 버튼: 현재 답변 닫기. 질문 전송·마이크 시작·업무 승인 기능은 없습니다.")
        Column(Modifier.fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (!focused) armed = false }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val device = key.device
                val reveal = key.keyCode in setOf(android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_BUTTON_A)
                val dismiss = key.keyCode in setOf(android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_BUTTON_B)
                if (!armed || !focused || device == null || device.isVirtual ||
                    !key.hasNoModifiers() || (!reveal && !dismiss)) false
                else {
                    if (key.action == android.view.KeyEvent.ACTION_DOWN && key.repeatCount == 0)
                        model.attempt { model.service().hudKey(source, reveal) }
                    true
                }
            }.focusable().padding(vertical = 8.dp)) {
            Text(if (armed && focused) "키 입력 대기 중" else "키 입력 비활성", style = MaterialTheme.typography.bodyMedium)
        }
        ToolOutlinedButton(onClick = {
            if (armed) armed = false else { requester.requestFocus(); armed = focused }
        }, enabled = model.state.glasses.link.phase == LinkPhase.FULL) {
            Text(if (armed && focused) "키 조작 해제" else "키 조작 활성화")
        }
        Note("포커스가 다른 화면이나 입력창으로 이동하면 해제됩니다. 실제 컨트롤러 키 매핑과 안경 다이얼은 별도 기기 확인이 필요합니다.")
    }
}

@Composable
private fun AgentProbeCard(model: AppModel) {
    val s = model.state
    val p = s.agentProbe
    var expanded by rememberSaveable { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(AgentProbePolicy.Target.DEFAULT) }
    ContentCard {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "AI 연결 진단 접기" else "AI 연결 및 안경 표시 진단")
        }
        if (expanded) {
            Note("선택한 DSH 모델 경로에 무작위 코드만 보내 응답을 확인합니다. 원문·개인화 정보·업무 자료·도구를 사용하지 않습니다. 이용량 또는 요금이 발생할 수 있습니다. 일반 Agent 업무 실행이나 제조사 AI 이용권을 검증하지 않습니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = target == AgentProbePolicy.Target.DEFAULT, onClick = { target = AgentProbePolicy.Target.DEFAULT }, label = { Text("기본 질문 모델") }, enabled = p.phase != AgentProbePolicy.Phase.WAITING)
                FilterChip(selected = target == AgentProbePolicy.Target.AMBIENT, onClick = { target = AgentProbePolicy.Target.AMBIENT }, label = { Text("상시 힌트 모델") }, enabled = p.phase != AgentProbePolicy.Phase.WAITING)
            }
            Note(when (p.phase) {
                AgentProbePolicy.Phase.IDLE -> "실행 전"
                AgentProbePolicy.Phase.WAITING -> "응답 대기 (최대 60초, 대기열 포함)"
                AgentProbePolicy.Phase.PASSED -> "테스트 코드 일치 · 휴대폰 응답 수신 확인"
                AgentProbePolicy.Phase.MISMATCH -> "응답은 도착했지만 코드 불일치"
                AgentProbePolicy.Phase.FAILED -> "응답 실패 또는 만료 · DSH 연결과 모델 설정 확인"
                AgentProbePolicy.Phase.CANCELLED -> "취소됨"
            })
            if (p.phase != AgentProbePolicy.Phase.IDLE) {
                Note("이번 진단 대상: " + if (p.target == AgentProbePolicy.Target.AMBIENT) "상시 힌트 모델" else "기본 질문 모델")
                val route = p.modelRoute
                if (route != null) {
                    Note("DSH 보고 경로: ${route.provider} / ${route.model}")
                    Note(when (route.selection) { "ambient_override" -> "상시 전용 모델"; "ambient_default" -> "상시 모델 미설정 · 기본 모델 사용"; else -> "기본 모델" })
                    if (!route.matches(p.target)) Note("요청한 경로와 보고 경로가 다릅니다. 플러그인 설정을 확인하세요.")
                } else if (p.phase != AgentProbePolicy.Phase.WAITING) Note("모델 경로 미보고 · DSH 플러그인 버전/연결 확인")
                Note("모델 표시는 DSH가 보고한 호출 경로이며 공급자 내부 실제 모델·계정·과금까지 증명하지 않습니다.")
            }
            if (p.elapsedMs != null) Note("전체 대기·응답 시간: ${p.elapsedMs}ms")
            if (p.code.isNotBlank()) Note("테스트 코드: ${p.code}")
            if (p.phase == AgentProbePolicy.Phase.WAITING) {
                TextButton(onClick = { model.attempt { model.service().cancelAgentProbe() } }) { Text("진단 취소") }
            } else {
                ToolButton(onClick = { consent = true }, enabled = s.available && s.agentServer.enabled &&
                    !s.phoneRunning && !s.computerRunning && !s.conversation.running && !s.translationActive &&
                    !s.teleprompter.smartFollow) { Text("AI 응답 진단 실행") }
            }
            if (p.phase == AgentProbePolicy.Phase.PASSED) {
                when (p.hud) {
                    AgentProbePolicy.Hud.NOT_SENT -> TextButton(onClick = { model.attempt { model.service().sendAgentProbeHud() } },
                        enabled = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting &&
                            !s.focusMode && s.appNotificationsEnabled) { Text("같은 코드를 안경 알림으로 보내기") }
                    AgentProbePolicy.Hud.REQUESTED -> {
                        Note("안경 알림 전송을 요청했습니다. 실제 표시 여부는 확인되지 않았습니다.")
                        TextButton(onClick = { model.attempt { model.service().confirmAgentProbeHud() } }) { Text("안경에서 같은 코드가 보였음") }
                    }
                    AgentProbePolicy.Hud.CONFIRMED -> Note("사용자가 실제 안경에서 같은 코드 표시를 확인함")
                }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text("AI 모델에 진단 요청을 보낼까요?") },
        text = { Text("선택한 DSH 모델 경로에 테스트 코드 한 건을 보냅니다. 이용량 또는 비용이 발생할 수 있습니다.") },
        confirmButton = { TextButton(onClick = { consent = false; model.attempt { model.service().startAgentProbe(target) } }) { Text("진단 실행") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("취소") } })
}

@Composable
private fun ConversationTurn(question: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Text(
                    question,
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (answer.isNotBlank())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "OpenRayneo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        answer.split("```").forEachIndexed { index, part ->
                            if (index % 2 == 0)
                                Text(part.trim(), style = MaterialTheme.typography.bodyLarge)
                            else
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        part.trim(),
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                        }
                    }
                }
            }
    }
}

@Composable
private fun AgentReceiptCard(model: AppModel, receipts: List<LocalAgentServer.Receipt>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ContentCard {
        TextButton(onClick = { expanded = !expanded }) { Text("최근 외부 작업 결과 (${receipts.size})") }
        if (expanded) {
            Note("현재 로컬 Agent 세션의 최근 상태입니다. 도구 응답과 실제 안경 상태를 구별합니다. 영구 작업 기록은 아닙니다.")
            receipts.forEach { receipt ->
                Text(receipt.name + " · " + receipt.status, fontWeight = FontWeight.Medium)
                if (receipt.detail.isNotBlank()) Note(receipt.detail)
                if (receipt.status == "실행 중") TextButton(onClick = {
                    model.attempt { model.service().agentServer.cancelReviewExecution(receipt.id) }
                }) { Text("남은 실행 취소") }
            }
        } else Note(receipts.first().name + " · " + receipts.first().status)
    }
}

@Composable
private fun AgentWriteReviewCard(model: AppModel, review: LocalAgentServer.Review) {
    ContentCard {
        Text("외부 Agent 작업 승인", style = MaterialTheme.typography.titleLarge)
        Note("요청 내용을 확인하세요. 휴대폰에서 승인해야 실행합니다. 거절하거나 40초 이내 승인하지 않으면 실행하지 않습니다.")
        Text(review.name, fontWeight = FontWeight.Medium)
        SelectionContainer { Text(review.arguments, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        Note("승인 기한: " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(review.expiresAt)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton(onClick = { model.attempt { model.service().agentServer.review(review.id, true) } }) { Text("이 작업만 승인") }
            ToolOutlinedButton(onClick = { model.attempt { model.service().agentServer.review(review.id, false) } }) { Text("거절") }
        }
    }
}

@Composable
private fun ApprovalCard(model: AppModel, request: CodexClient.Request) {
    val answers = remember(request.id) { mutableStateMapOf<String, String>() }
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null)
            Text("컴퓨터가 응답을 기다립니다", style = MaterialTheme.typography.titleLarge)
            if (request.method == "item/tool/requestUserInput") {
                val questions = request.params.getJSONArray("questions")
                for (index in 0 until questions.length()) {
                    val question = questions.getJSONObject(index)
                    val id = question.getString("id")
                    Text(question.getString("question"), fontWeight = FontWeight.Medium)
                    question.optJSONArray("options")?.let { options ->
                        for (i in 0 until options.length()) {
                            val option = options.getJSONObject(i)
                            FilterChip(
                                selected = answers[id] == option.optString("label"),
                                onClick = { answers[id] = option.optString("label") },
                                label = { Text(option.optString("label")) },
                            )
                            if (option.optString("description").isNotBlank())
                                Note(option.optString("description"))
                        }
                    }
                    OutlinedTextField(
                        answers[id].orEmpty(),
                        { answers[id] = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("내 응답") },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    if (question.optBoolean("isSecret"))
                                        androidx.compose.ui.text.input.KeyboardType.Password
                                    else androidx.compose.ui.text.input.KeyboardType.Text
                            ),
                        visualTransformation =
                            if (question.optBoolean("isSecret"))
                                androidx.compose.ui.text.input.PasswordVisualTransformation()
                            else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                ToolButton(
                    onClick = {
                        model.attempt {
                            val response = JSONObject()
                            answers.forEach { (id, value) ->
                                response.put(
                                    id,
                                    JSONObject().put("answers", JSONArray().put(value)),
                                )
                            }
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("answers", response))
                        }
                    },
                    enabled =
                        (0 until questions.length()).all {
                            answers[questions.getJSONObject(it).getString("id")]?.isNotBlank() ==
                                true
                        },
                ) {
                    Text("응답 제출")
                }
            } else {
                SelectionContainer {
                    Text(request.details, style = MaterialTheme.typography.bodyMedium)
                }
                ToolButton(
                    onClick = {
                        model.attempt {
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("decision", "accept"))
                        }
                    }
                ) {
                    Text("이번만 허용")
                }
                ToolOutlinedButton(
                    onClick = {
                        model.attempt {
                            model
                                .service()
                                .codex
                                .reply(request, JSONObject().put("decision", "decline"))
                        }
                    }
                ) {
                    Text("거부")
                }
            }
        }
    }
}
