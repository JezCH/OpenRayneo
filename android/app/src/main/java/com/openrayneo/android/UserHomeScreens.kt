// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(model: AppModel, navigate: (Int) -> Unit, open: (String) -> Unit, connect: () -> Unit) {
    val s = model.state
    val linked = s.available && s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val c = s.conversation
    val otherMode = s.translationActive || s.captionActive || s.mediaCaption.busy || s.mediaTranslation.preparing || s.offlinePreparing || s.speechPlaying || s.wakeActive || s.wakePending ||
        s.nativeWakeAvailable || s.teleprompter.active != null || s.teleprompter.busy || s.migration.busy ||
        s.phoneRunning || s.computerRunning || s.glasses.navigation.enabled || s.glasses.session != null ||
        s.glasses.intent != DisplayIntent.Idle
    var requested by remember { mutableStateOf<ConversationMode?>(null) }
    var hintRequested by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    val activity = when {
        !s.available -> "서비스 상태 확인 중"
        s.alwaysOnResumePending || c.stopping || c.running -> recording(s).label
        s.wakeActive -> "한국어 호출 대기 중"
        s.wakePending -> "한국어 호출 응답 대기 중"
        s.mediaCaption.busy -> "미디어 자막 · " + s.mediaCaption.detail
        s.captionActive -> "실시간 자막 중"
        s.translationActive -> "실시간 번역 중"
        s.teleprompter.active != null -> "프롬프터 사용 중"
        s.teleprompter.busy -> "프롬프터 준비 중"
        s.glasses.navigation.enabled -> "길 안내 중"
        s.phoneRunning || s.computerRunning -> "AI 응답 대기 중"
        s.migration.busy -> "기록 이전 중"
        s.mediaTranslation.preparing || s.offlinePreparing -> "번역 엔진 준비 중"
        s.speechPlaying -> "음성 재생 중"
        s.nativeWakeAvailable -> "안경 기본 호출 처리 중"
        c.summarizing -> "기록 요약 중"
        otherMode -> "안경 화면 사용 중"
        else -> "대기 중"
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ContentCard {
                Text("현재 활동", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Text(activity, style = MaterialTheme.typography.headlineSmall)
                }
                Note("음성 인식: " + when {
                    !s.available -> "서비스 준비 중"
                    !model.speechConfigured -> "선택한 인식 모델 설정 필요"
                    SpeechRounds.model(model.config) == null -> "외부 음성 인식 설정됨"
                    else -> "기기 내장 모델 확보됨"
                })
                if (!c.modelReady) Note("기록 엔진 초기화 대기")
                if (model.config.optBoolean("ambientWakeEnabled") && c.running && !c.reprocessing && (c.source == ConversationSource.ALWAYS_ON || (c.source == ConversationSource.REALTIME && model.config.optBoolean("fastRealtimeHints")))) Note("한국어 호출: " + s.ambientWakeStatus)
                if (c.running || c.stopping || s.alwaysOnResumePending) {
                    ToolButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !c.stopping,
                        modifier = Modifier.fillMaxWidth()) { Text(if (c.stopping) "마무리 중…" else if (c.reprocessing) "재전사 중지" else "기록 중지") }
                } else if (s.wakeActive || s.wakePending) {
                    ToolButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }, modifier = Modifier.fillMaxWidth()) { Text("한국어 호출 중지") }
                } else if (s.mediaCaption.busy) {
                    ToolButton(onClick = { model.attempt { model.service().stopMediaCaption() } }, enabled = s.mediaCaption.phase != MediaCapturePhase.STOPPING, modifier = Modifier.fillMaxWidth()) { Text("미디어 자막 종료") }
                } else if (s.captionActive) {
                    ToolButton(onClick = { model.attempt { model.service().stopCaption() } }, modifier = Modifier.fillMaxWidth()) { Text("자막 종료") }
                } else if (s.translationActive) {
                    ToolButton(onClick = { model.attempt { model.service().stopTranslation() } }, modifier = Modifier.fillMaxWidth()) { Text("번역 종료") }
                } else if (s.teleprompter.active != null || s.teleprompter.busy) {
                    ToolButton(onClick = { open("teleprompter") }, modifier = Modifier.fillMaxWidth()) { Text("프롬프터 제어") }
                } else if (s.glasses.navigation.enabled) {
                    ToolButton(onClick = { open("navigation") }, modifier = Modifier.fillMaxWidth()) { Text("길 안내 제어") }
                } else if (s.phoneRunning || s.computerRunning) {
                    ToolButton(onClick = { navigate(1) }, modifier = Modifier.fillMaxWidth()) { Text("AI 작업 확인") }
                } else if (s.migration.busy) {
                    ToolButton(onClick = { open("record-migration") }, modifier = Modifier.fillMaxWidth()) { Text("기록 이전 확인") }
                } else if (s.mediaTranslation.preparing || s.offlinePreparing) {
                    ToolButton(onClick = { open(if (s.mediaTranslation.language == MediaTranslationRoute.BILINGUAL || s.offlinePreparing) "translation" else "media-captions") }, modifier = Modifier.fillMaxWidth()) { Text("번역 준비 상태") }
                } else if (s.speechPlaying) {
                    ToolButton(onClick = { model.attempt { model.service().speechOutput.stop() } }, modifier = Modifier.fillMaxWidth()) { Text("음성 재생 중지") }
                } else if (s.nativeWakeAvailable) {
                    ToolButton(onClick = { open("korean-wake") }, modifier = Modifier.fillMaxWidth()) { Text("호출 상태 확인") }
                } else {
                    ToolButton(onClick = {
                        if (!linked) connect()
                        else if (!c.modelReady) open("voice-settings")
                        else if (otherMode) open("display-controls")
                        else { resume = false; requested = ConversationMode.DAILY }
                    }, enabled = !c.stopping, modifier = Modifier.fillMaxWidth()) {
                        Text(when { !linked -> "안경 연결"; !c.modelReady -> "음성 인식 준비"; otherMode -> "현재 화면 제어"; else -> "일상 기록 시작" })
                    }
                }
                if (c.error != null) Text(c.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 연결", style = MaterialTheme.typography.labelLarge)
                        Note(selectedAiStatus(model).label)
                        if (!model.aiLegacy && model.aiConfigured) Note(model.aiStatus)
                    }
                    TextButton(onClick = { open(if (model.aiLegacy) "agent-api" else "model") }) { Text("관리") }
                }
            }
        }
        item {
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.deviceName, style = MaterialTheme.typography.titleMedium)
                        Note(if (linked) "BLE·SPP 연결 · " + s.deviceSettings.readback.label else s.glasses.link.detail)
                        if (!linked) s.glasses.link.lastFailure?.let { Note("최근 연결 원인: $it") }
                        if (linked && s.deviceSettings.loaded) s.deviceSettings.status.deviceInt("battery")?.takeIf { it in 0..100 }?.let { Note("배터리 $it%") }
                    }
                    TextButton(onClick = { if (linked || s.glasses.link.reconnectWaiting) navigate(4) else connect() }) { Text(if (linked || s.glasses.link.reconnectWaiting) "관리" else "연결") }
                }
                if (s.glasses.link.reconnectWaiting) TextButton(onClick = { model.attempt { model.service().disconnect() } }, enabled = s.available) { Text("자동 재연결 중지") }
            }
        }
        if (s.approvals.isNotEmpty() || s.agentReviews.isNotEmpty()) item {
            FeatureLink("확인이 필요한 AI 작업 ${s.approvals.size + s.agentReviews.size}건", "내용 확인 후 승인 또는 거절", Icons.Outlined.AutoAwesome) { navigate(1) }
        }
        item { SectionLabel("바로 사용") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureTile("AI에게 질문", "질문 · PC 작업", Icons.AutoMirrored.Outlined.Chat, Modifier.weight(1f)) { navigate(1) }
                FeatureTile("회의 기록", "전사 · 요약", Icons.Outlined.GraphicEq, Modifier.weight(1f)) {
                    if (!linked) connect() else if (!c.modelReady) open("voice-settings")
                    else if (c.running || c.stopping || otherMode || s.alwaysOnResumePending) navigate(2)
                    else { resume = false; requested = ConversationMode.MEETING }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureTile("실시간 힌트", "대화 중 짧은 도움", Icons.Outlined.AutoAwesome, Modifier.weight(1f)) {
                    if (!linked) connect() else if (!c.modelReady || !model.speechConfigured) open("voice-settings")
                    else if (c.running || c.stopping || otherMode || s.alwaysOnResumePending) navigate(2)
                    else if (!model.aiConfigured || !model.aiReady) open("model") else hintRequested = true
                }
                FeatureTile("한국어 호출", "내 호출어·말로 AI 사용", Icons.Outlined.Mic, Modifier.weight(1f)) { open("korean-wake") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureTile("영상 자막", "휴대폰 소리 → 한국어", Icons.Outlined.Translate, Modifier.weight(1f)) { open("media-captions") }
                FeatureTile("한영 통역", "안경 마이크 · 내장 번역", Icons.Outlined.Translate, Modifier.weight(1f)) { open("translation") }
            }
        }
        item { FeatureLink("모든 도구", "대화 자막 · 프롬프터 · 길 안내 · 일정", Icons.Outlined.Dashboard) { navigate(3) } }
        item { SectionLabel("관리") }
        item { FeatureLink("자동 기능 관리", if (model.recommendedPending) "호출·힌트·기억·알림 설정 확인" else "호출·힌트·기억·알림의 실행 상태", Icons.Outlined.Tune) { open("feature-controls") } }
        if (AppUpdate.supported && model.updateCheck.candidate != null && model.updateCheck.error.isBlank())
            item { FeatureLink("앱 업데이트", model.updateCheck.summary, Icons.Outlined.Settings) { open("app-update") } }
        item {
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("집중 모드", style = MaterialTheme.typography.titleMedium)
                        Note("기록은 유지하고 자동 힌트와 일부 알림을 제한")
                    }
                    Switch(checked = s.focusMode, enabled = linked && s.available, onCheckedChange = { enabled ->
                        model.attempt { model.service().applyFocusCommand(if (enabled) FocusPolicy.Command.FOCUS_ON else FocusPolicy.Command.FOCUS_OFF) }
                    })
                }
            }
        }
        item {
            ContentCard {
                Text("기록 및 기억", style = MaterialTheme.typography.titleMedium)
                Note("저장된 기록 " + c.records.size + "개")
                TextButton(onClick = { navigate(2) }) { Text("기록 찾아보기") }
            }
        }
    }
    requested?.let { mode ->
        AlertDialog(onDismissRequest = { requested = null },
            title = { Text(mode.title + " 기록을 시작할까요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("안경 마이크의 대화를 인식하고 기록합니다. 현재 보존 설정에 따라 원본 음성이 저장될 수 있으며, 설정된 AI 서비스에 전사가 전달될 수 있습니다. 참여자 동의와 장소 보안 규칙을 확인하세요.")
                    if (mode == ConversationMode.DAILY) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(resume, { resume = it })
                        Text("이번 기록만 같은 안경 재연결 시 자동 재개 (서비스 종료 전까지)")
                    }
                }
            },
            confirmButton = { TextButton(enabled = linked && c.modelReady && !c.running && !c.stopping && !otherMode && !s.alwaysOnResumePending,
                onClick = {
                    requested = null
                    model.attempt { model.service().startConversation(mode, ConversationSource.ALWAYS_ON, resumeOnReconnect = mode == ConversationMode.DAILY && resume) }
                }) { Text("동의하고 시작") } },
            dismissButton = { TextButton(onClick = { requested = null }) { Text("취소") } })
    }
    if (hintRequested) AlertDialog(onDismissRequest = { hintRequested = false },
        title = { Text("실시간 힌트를 시작할까요?") },
        text = { Text("안경 음성을 인식하고 일상 기록을 시작합니다. 현재 발화는 연결된 AI 모델에 전송될 수 있으며 이용량이나 요금이 발생할 수 있습니다. 참여자 동의와 보안 규칙을 확인하세요.") },
        confirmButton = { TextButton(enabled = linked && c.modelReady && model.speechConfigured && model.aiConfigured && model.aiReady && !c.running && !c.stopping && !otherMode && !s.alwaysOnResumePending,
            onClick = { hintRequested = false; model.attempt { model.service().startRealtimeHint() } }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { hintRequested = false }) { Text("취소") } })
}

@Composable
internal fun ToolsScreen(open: (String) -> Unit, navigate: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionLabel("자막 및 번역") }
        item { FeatureLink("유튜브·영상 자막", "휴대폰 영상 소리 → 원문 또는 한국어 자막", Icons.Outlined.Translate) { open("media-captions") } }
        item { FeatureLink("주변 대화 자막", "안경 마이크로 듣고 글자로 표시", Icons.Outlined.GraphicEq) { open("captions") } }
        item { FeatureLink("주변 대화 번역", "한국어 ↔ 영어 · 내장 모델 · 인터넷 불필요", Icons.Outlined.Translate) { open("translation") } }
        item { Spacer(Modifier.height(8.dp)); SectionLabel("AI 및 기록") }
        item { FeatureLink("한국어 호출", "내 호출어 설정 · 듣기 시작/중지", Icons.Outlined.Mic) { open("korean-wake") } }
        item { FeatureLink("일상·회의 기록", "대화 기록 시작 · 원문 및 요약 보기", Icons.Outlined.GraphicEq) { navigate(2) } }
        item { Spacer(Modifier.height(8.dp)); SectionLabel("생활 및 업무") }
        item { FeatureLink("프롬프터", "원고 가져오기 · 안경에서 읽기", Icons.Outlined.Description) { open("teleprompter") } }
        item { FeatureLink("길 안내", "네이버지도 길 안내를 안경에 표시", Icons.Outlined.NearMe) { open("navigation") } }
        item { FeatureLink("할 일 · 일정 · 날씨", "안경 홈 화면과 생활 정보 관리", Icons.Outlined.Dashboard) { open("content") } }
        item { FeatureLink("타이머 · 스톱워치", "직접 시작 · 남은 시간 · 정지", Icons.Outlined.Dashboard) { open("local-clock") } }
        item { Spacer(Modifier.height(8.dp)); SectionLabel("안경 확인") }
        item { FeatureLink("안경 화면 및 오디오", "현재 표시 · 수신 상태 · 중지", Icons.Outlined.Visibility) { open("display-controls") } }
        item { Note("안경 화면과 마이크를 공유하는 기능은 동시에 사용하지 못할 수 있습니다. 실행 중인 기능은 홈에서 중지하세요.") }
    }
}

@Composable
internal fun TranslationToolScreen(model: AppModel) {
    val s = model.state
    var confirm by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("bundled") }
    val bundled = mode == "bundled"
    val local = mode == "external"
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingStart by remember { mutableStateOf(false) }
    val mt = s.mediaTranslation
    var address by remember { mutableStateOf(model.config.optString("offlineTranslationUrl").ifBlank { "http://127.0.0.1:8080/v1" }) }
    var translationModel by remember { mutableStateOf(model.config.optString("offlineTranslationModel")) }
    var localKey by remember { mutableStateOf(model.config.optString("offlineTranslationKey")) }
    val prepared = s.offlineReady && address.trim() == model.config.optString("offlineTranslationUrl") && translationModel.trim() == model.config.optString("offlineTranslationModel") && localKey.trim() == model.config.optString("offlineTranslationKey")
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val problem = if (s.translationActive) null else if (!s.available) "연결 서비스 준비 중" else
        if (local && !prepared) "저장된 로컬 번역 모델 연결을 먼저 확인하세요" else
            runCatching { model.service().checkTranslation(local, bundled, requirePrepared = false) }.exceptionOrNull()?.let { it.message ?: "번역 준비 상태 확인 실패" }
    LaunchedEffect(pendingStart, mt.phase, mt.language, bundled, s.available) {
        if (!pendingStart) return@LaunchedEffect
        if (!bundled || !s.available) { pendingStart = false; return@LaunchedEffect }
        if (mt.preparing) return@LaunchedEffect
        pendingStart = false
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        if (mt.readyFor(MediaTranslationRoute.BILINGUAL) && lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
            model.attempt { model.service().startTranslation(bundled = true) }
        else if (mt.readyFor(MediaTranslationRoute.BILINGUAL))
            model.notice = "내장 통역 준비 완료 · 화면에서 다시 시작하세요."
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("한국어 ↔ 영어", style = MaterialTheme.typography.headlineSmall)
            Note(s.translationStatus)
            if (!s.translationActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(bundled, { mode = "bundled" }, enabled = !mt.preparing); Text("앱 내장 한영 통역")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(local, { mode = "external" }, enabled = !mt.preparing); Text("외부 로컬 서버 (고급)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == "cloud", { mode = "cloud" }, enabled = !mt.preparing); Text("기존 AI 번역 (고급)")
                }
                if (bundled) {
                    Note("안경 마이크의 한국어·영어를 인식하여 양방향 번역합니다. 별도 모델 설치·서버·인터넷이 필요하지 않습니다.")
                    if (mt.language == MediaTranslationRoute.BILINGUAL) Note(mt.detail)
                    if (pendingStart && mt.preparing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        ToolOutlinedButton(onClick = { pendingStart = false; model.attempt { model.service().cancelMediaTranslationPreparation() } }) { Text("시작 취소") }
                    }
                } else if (local) {
                    Note(s.offlineStatus)
                    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("휴대폰 로컬 서버 주소") }, singleLine = true)
                    OutlinedTextField(translationModel, { translationModel = it }, Modifier.fillMaxWidth(), label = { Text("로컬 모델 이름") }, singleLine = true)
                    OutlinedTextField(localKey, { localKey = it }, Modifier.fillMaxWidth(), label = { Text("로컬 API 키 (선택)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    ToolOutlinedButton(onClick = {
                        model.saveConfig(mapOf("offlineTranslationUrl" to address, "offlineTranslationModel" to translationModel, "offlineTranslationKey" to localKey))
                        model.attempt {
                            check(address.trim() == model.config.optString("offlineTranslationUrl") &&
                                translationModel.trim() == model.config.optString("offlineTranslationModel") &&
                                localKey.trim() == model.config.optString("offlineTranslationKey")) { "로컬 번역 설정 저장 실패" }
                            model.service().translator.offline.prepare(model.config)
                        }
                    }, enabled = !s.offlinePreparing && translationModel.isNotBlank()) { Text("로컬 모델 연결 시험") }
                    Note("휴대폰에서 실행 중인 llama.cpp 호환 번역 모델이 필요합니다. SenseVoice 또는 Nemotron 로컬 음성 모델과 함께 사용합니다. 이 앱은 127.0.0.1에만 연결합니다. 로컬 서버 자체가 클라우드를 중계하지 않는지 확인하세요. 이 선택은 별도의 로컬 서버를 계속 이용하려는 경우에만 사용합니다.")
                } else Note("설정된 ASR 및 AI 서비스에 발화가 전달될 수 있으며 비용이 발생할 수 있습니다.")
            }
            if (s.wakeActive || s.wakePending) Note("한국어 호출을 먼저 종료하세요.")
            else if (s.mediaCaption.busy) Note("미디어 자막을 먼저 종료하세요.")
            else if (s.captionActive) Note("실시간 자막을 먼저 종료하세요.")
            else if (s.translationActive) ToolButton(onClick = { model.attempt { model.service().stopTranslation() } }) { Text("번역 종료") }
            else {
                problem?.let { Note("시작할 수 없는 이유: " + it) }
                ToolButton(onClick = { confirm = true }, enabled = problem == null && !pendingStart && !mt.preparing) { Text(if (pendingStart) "통역 준비 중…" else "번역 시작") }
            }
            if (!linked) Note("먼저 안경을 연결하세요.")
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("실시간 번역을 시작할까요?") },
        text = { Text(if (bundled) "안경 마이크와 앱 내장 음성·번역 모델을 사용합니다. 인터넷으로 발화를 보내거나 녹음을 저장하지 않습니다. 참여자 동의와 장소 보안 규칙을 확인하세요." else if (local) "로컬 음성 인식과 휴대폰 내 모델 서버를 사용합니다. 서버의 오프라인 설정을 확인하세요. 다른 기록과 화면 기능은 먼저 종료해야 합니다. 참여자 동의와 장소 보안 규칙을 확인하세요." else "다른 기록과 화면 기능은 먼저 종료해야 하며 음성 및 텍스트가 설정된 서비스로 전송될 수 있습니다. 참여자 동의와 이용량을 확인하세요.") },
        confirmButton = { TextButton(enabled = problem == null && !s.translationActive,
            onClick = {
                confirm = false
                model.attempt {
                    if (bundled && !mt.readyFor(MediaTranslationRoute.BILINGUAL)) {
                        model.service().checkTranslation(false, bundled = true, requirePrepared = false)
                        model.service().prepareMediaTranslation(MediaTranslationRoute.BILINGUAL)
                        pendingStart = true
                    } else model.service().startTranslation(local, bundled)
                }
            }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
}

/** Explicitly yield our transport; never infer or change another app's connection. */
@Composable
internal fun ConnectionReleaseControls(model: AppModel) {
    val s = model.state
    val link = s.glasses.link
    val releasing = s.glasses.disconnecting || link.phase == LinkPhase.CLOSING
    val canRelease = link.reconnectWaiting || link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY, LinkPhase.CONNECTING, LinkPhase.SCANNING, LinkPhase.RETRY_WAIT)
    Note("두 앱은 함께 설치할 수 있지만 안경의 동시 제어는 검증되지 않았습니다. 전환할 때는 현재 앱의 안경 연결과 자동 재연결을 먼저 종료하세요.")
    if (releasing) Note("검증 앱 연결 해제 중 · 완료 후 공식 앱을 여세요.")
    else if (canRelease) {
        ToolOutlinedButton(onClick = { model.attempt { model.service().disconnect() } }, enabled = s.available) {
            Text(if (link.reconnectWaiting) "자동 재연결 중지 · 연결 반환" else "연결 해제 · 공식 앱 사용")
        }
        Note("실행하면 검증 앱의 안경 연결과 기록 자동 복구가 종료됩니다. 페어링, 계정, 저장된 기록은 유지됩니다.")
    } else if (s.available && link.phase == LinkPhase.DISCONNECTED) {
        Note("검증 앱은 안경 연결 및 자동 재연결 대기 상태가 아닙니다. 공식 앱에서 연결할 수 있습니다. 검증 앱으로 돌아올 때는 공식 앱의 안경 연결을 먼저 끊으세요.")
    } else Note("연결 상태 확인 중")
}

@Composable
internal fun DeviceOverview(model: AppModel, connect: () -> Unit, open: (String) -> Unit) {
    val s = model.state
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ContentCard {
            GlassesArtwork(Modifier.fillMaxWidth().height(90.dp), MaterialTheme.colorScheme.primary)
            Text(model.deviceName, style = MaterialTheme.typography.headlineSmall)
            Note(if (linked) "BLE·SPP 연결 · " + s.deviceSettings.readback.label else s.glasses.link.detail)
            val managing = s.glasses.link.reconnectWaiting || s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY, LinkPhase.CONNECTING, LinkPhase.SCANNING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
            ToolButton(onClick = { if (managing) open("display-controls") else connect() }, modifier = Modifier.fillMaxWidth()) { Text(if (managing) "연결 관리" else "안경 연결") }
        } }
        item { FeatureLink("안경 설정", "밝기 · 배터리 · 화면 위치 · 제스처 · 언어", Icons.Outlined.Settings) { open("device-settings") } }
        item { FeatureLink("휴대폰 알림", "앱별 전달 · 본문 표시", Icons.Outlined.NotificationsNone) { open("phone-notifications") } }
        item { FeatureLink("홈 화면 및 위젯", "레이아웃 · 콘텐츠", Icons.Outlined.Dashboard) { open("content") } }
        item { FeatureLink("화면 및 오디오", "현재 표시 · 수신 상태 · 중지", Icons.Outlined.Visibility) { open("display-controls") } }
        item { SectionLabel("고급 및 문제 해결") }
        item { FeatureLink("연결 문제 해결", "테스트 알림 · 연결 로그", Icons.Outlined.Tune) { open("diagnostics") } }
        item { FeatureLink("한글 표시 검사", "내장 폰트 · 글리프 확인", Icons.Outlined.Visibility) { open("korean-glyphs") } }
        item { ContentCard {
            Text("다른 앱과 연결 전환", style = MaterialTheme.typography.titleMedium)
            ConnectionReleaseControls(model)
        } }
    }
}

@Composable
internal fun CaptionToolScreen(model: AppModel, voiceSettings: () -> Unit, mediaCaptions: () -> Unit) {
    val s = model.state
    val context = model.getApplication<android.app.Application>()
    val config = model.config
    val localReady = SpeechRounds.model(config) != null && SpeechRounds.ready(context, config)
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val busy = s.translationActive || s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending ||
        s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null || s.glasses.navigation.enabled ||
        s.teleprompter.active != null || s.teleprompter.busy || s.nativeWakeAvailable || s.phoneRunning || s.computerRunning
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("주변 대화 자막", style = MaterialTheme.typography.headlineSmall)
            Note("유튜브나 이어폰의 내부 재생음을 자막으로 보려면 아래 미디어 자막을 사용하세요.")
            ToolOutlinedButton(onClick = mediaCaptions) { Text("유튜브·미디어 자막 열기") }
            Note(s.captionStatus)
            Note("안경 마이크로 들은 말을 휴대폰의 로컬 음성 모델로 인식해 안경에 표시합니다. 번역·AI 요청·대화 기록 저장을 하지 않습니다. 모델을 내려받은 후에는 네트워크 없이 사용할 수 있습니다.")
            if (s.captionActive) ToolButton(onClick = { model.attempt { model.service().stopCaption() } }) { Text("자막 종료") }
            else {
                if (!linked) Note("먼저 안경을 연결하세요.")
                if (!localReady) {
                    Note("기기 내장 음성 모델을 선택하고 설치해야 합니다. 클라우드로 자동 전환하지 않습니다.")
                    ToolOutlinedButton(onClick = voiceSettings) { Text("음성 인식 설정") }
                }
                if (busy) Note("다른 기록·안경 화면·AI 작업을 먼저 종료하세요.")
                ToolButton(onClick = { confirm = true }, enabled = linked && localReady && !busy) { Text("자막 시작") }
            }
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("실시간 자막을 시작할까요?") },
        text = { Text("안경 마이크를 사용합니다. 원문과 음성은 저장하지 않지만 주변 참여자의 동의와 장소 보안 규칙을 확인하세요.") },
        confirmButton = { TextButton(enabled = linked && localReady && !busy, onClick = {
            confirm = false
            model.attempt { model.service().startCaption() }
        }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
}
