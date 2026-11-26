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
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val c = s.conversation
    val otherMode = s.translationActive || s.teleprompter.active != null || s.teleprompter.busy ||
        s.glasses.navigation.enabled || s.glasses.session != null || s.glasses.intent != DisplayIntent.Idle
    var requested by remember { mutableStateOf<ConversationMode?>(null) }
    var hintRequested by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    val activity = when {
        s.alwaysOnResumePending -> "기록 재개 대기"
        c.stopping -> "기록 저장 중"
        c.running -> c.mode.title + " 기록 중"
        s.wakeActive -> "한국어 호출 대기 중"
        s.wakePending -> "한국어 호출 응답 대기 중"
        s.mediaCaption.busy -> "미디어 자막 · " + s.mediaCaption.detail
        s.captionActive -> "실시간 자막 중"
        s.translationActive -> "실시간 번역 중"
        s.teleprompter.active != null -> "프롬프터 사용 중"
        s.teleprompter.busy -> "프롬프터 준비 중"
        s.glasses.navigation.enabled -> "길 안내 중"
        s.phoneRunning || s.computerRunning -> "AI 응답 대기 중"
        else -> "대기 중"
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.deviceName, style = MaterialTheme.typography.titleMedium)
                        Note(if (linked) "안경 연결됨" else s.glasses.link.detail)
                    }
                    TextButton(onClick = { if (linked) navigate(4) else connect() }) { Text(if (linked) "관리" else "연결") }
                }
            }
        }
        item {
            ContentCard {
                Text("현재 활동", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Text(activity, style = MaterialTheme.typography.headlineSmall)
                }
                Note("음성 수신: " + s.glasses.audio.detail)
                Note("음성 인식: " + if (c.modelReady) "준비됨" else "설정 또는 모델 준비 필요")
                if (model.config.optBoolean("ambientWakeEnabled") && c.running && (c.source == ConversationSource.ALWAYS_ON || (c.source == ConversationSource.REALTIME && model.config.optBoolean("fastRealtimeHints")))) Note("한국어 호출: " + s.ambientWakeStatus)
                Note("AI: " + if (s.agentServer.enabled) "연결 기능 켜짐 · 실제 응답은 별도 확인" else "연결 설정 필요")
                if (c.running || s.alwaysOnResumePending) {
                    ToolButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !c.stopping,
                        modifier = Modifier.fillMaxWidth()) { Text(if (c.stopping) "저장 중…" else "기록 중지") }
                } else if (s.wakeActive || s.wakePending) {
                    ToolButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }, modifier = Modifier.fillMaxWidth()) { Text("한국어 호출 중지") }
                } else if (s.mediaCaption.busy) {
                    ToolButton(onClick = { model.attempt { model.service().stopMediaCaption() } }, enabled = s.mediaCaption.phase != MediaCapturePhase.STOPPING, modifier = Modifier.fillMaxWidth()) { Text("미디어 자막 종료") }
                } else if (s.captionActive) {
                    ToolButton(onClick = { model.attempt { model.service().stopCaption() } }, modifier = Modifier.fillMaxWidth()) { Text("자막 종료") }
                } else if (s.translationActive) {
                    ToolButton(onClick = { model.attempt { model.service().stopTranslation() } }, modifier = Modifier.fillMaxWidth()) { Text("번역 종료") }
                } else if (s.teleprompter.active != null) {
                    ToolButton(onClick = { open("teleprompter") }, modifier = Modifier.fillMaxWidth()) { Text("프롬프터 제어") }
                } else if (s.glasses.navigation.enabled) {
                    ToolButton(onClick = { open("navigation") }, modifier = Modifier.fillMaxWidth()) { Text("길 안내 제어") }
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
                if (!s.agentServer.enabled) TextButton(onClick = { open("agent-api") }) { Text("AI 연결 설정") }
            }
        }
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
                    if (!linked) connect() else if (!c.modelReady) open("voice-settings")
                    else if (c.running || c.stopping || otherMode || s.alwaysOnResumePending) navigate(2)
                    else if (!s.agentServer.enabled) open("agent-api") else hintRequested = true
                }
                FeatureTile("실시간 번역", "한국어 ↔ 영어", Icons.Outlined.Translate, Modifier.weight(1f)) { open("translation") }
            }
        }
        item { FeatureTile("미디어 자막", "이어폰으로 듣는 재생음 · 원문 자막", Icons.Outlined.Translate, Modifier.fillMaxWidth()) { open("media-captions") } }
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
        confirmButton = { TextButton(enabled = linked && c.modelReady && s.agentServer.enabled && !c.running && !c.stopping && !otherMode && !s.alwaysOnResumePending,
            onClick = { hintRequested = false; model.attempt { model.service().startRealtimeHint() } }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { hintRequested = false }) { Text("취소") } })
}

@Composable
internal fun ToolsScreen(open: (String) -> Unit, navigate: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Note("필요한 기능을 선택하세요. 안경 화면과 마이크를 공유하는 기능은 동시에 실행되지 않을 수 있습니다.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureTile("실시간 번역", "한국어 ↔ 영어", Icons.Outlined.Translate, Modifier.weight(1f)) { open("translation") }
            FeatureTile("프롬프터", "원고 · 따라 읽기", Icons.Outlined.Description, Modifier.weight(1f)) { open("teleprompter") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureTile("길 안내", "Amap · 국내 지도 실험", Icons.Outlined.NearMe, Modifier.weight(1f)) { open("navigation") }
            FeatureTile("회의 기록", "기록 화면 열기", Icons.Outlined.GraphicEq, Modifier.weight(1f)) { navigate(2) }
        } }
        item { FeatureTile("한국어 호출", "종일 호출 · 호출어 설정", Icons.Outlined.GraphicEq, Modifier.fillMaxWidth()) { open("korean-wake") } }
        item { FeatureTile("미디어 자막", "YouTube·내부 재생음 원문 자막", Icons.Outlined.Translate, Modifier.fillMaxWidth()) { open("media-captions") } }
        item { FeatureTile("실시간 자막", "로컬 음성 인식 · AI 없이 표시", Icons.Outlined.GraphicEq, Modifier.fillMaxWidth()) { open("captions") } }
        item { FeatureTile("할 일 · 일정 · 날씨", "안경 콘텐츠 관리", Icons.Outlined.Dashboard, Modifier.fillMaxWidth()) { open("content") } }
        item { ContentCard {
            Text("타이머 · 스톱워치", style = MaterialTheme.typography.titleMedium)
            Note("현재는 안경 음성 깨우기 명령으로 사용합니다. 예: 안경 타이머 5분 시작해줘. 중요 안전 알람으로 사용하지 마세요.")
            TextButton(onClick = { open("voice-settings") }) { Text("음성 도구 설정 및 사용법") }
        } }
        item { FeatureTile("안경 출력 확인", "화면 미리보기 · 지우기 · 오디오", Icons.Outlined.Visibility, Modifier.fillMaxWidth()) { open("display-controls") } }
    }
}

@Composable
internal fun TranslationToolScreen(model: AppModel) {
    val s = model.state
    var confirm by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf(model.config.optString("offlineTranslationUrl").ifBlank { "http://127.0.0.1:8080/v1" }) }
    var translationModel by remember { mutableStateOf(model.config.optString("offlineTranslationModel")) }
    var localKey by remember { mutableStateOf(model.config.optString("offlineTranslationKey")) }
    val prepared = s.offlineReady && address.trim() == model.config.optString("offlineTranslationUrl") && translationModel.trim() == model.config.optString("offlineTranslationModel") && localKey.trim() == model.config.optString("offlineTranslationKey")
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("한국어 ↔ 영어", style = MaterialTheme.typography.headlineSmall)
            Note(s.translationStatus)
            if (!s.translationActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(local, { local = true }); Text("휴대폰 로컬 번역")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!local, { local = false }); Text("기존 AI 번역")
                }
                if (local) {
                    Note(s.offlineStatus)
                    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("휴대폰 로컬 서버 주소") }, singleLine = true)
                    OutlinedTextField(translationModel, { translationModel = it }, Modifier.fillMaxWidth(), label = { Text("로컬 모델 이름") }, singleLine = true)
                    OutlinedTextField(localKey, { localKey = it }, Modifier.fillMaxWidth(), label = { Text("로컬 API 키 (선택)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    ToolOutlinedButton(onClick = {
                        model.saveConfig(mapOf("offlineTranslationUrl" to address, "offlineTranslationModel" to translationModel, "offlineTranslationKey" to localKey))
                        model.attempt { model.service().translator.offline.prepare(model.config) }
                    }, enabled = !s.offlinePreparing && translationModel.isNotBlank()) { Text("로컬 모델 연결 시험") }
                    Note("휴대폰에서 실행 중인 llama.cpp 호환 번역 모델이 필요합니다. SenseVoice 또는 Nemotron 로컬 음성 모델과 함께 사용합니다. 이 앱은 127.0.0.1에만 연결합니다. 로컬 서버 자체가 클라우드를 중계하지 않는지 확인하세요. 모델은 아직 앱에 내장되지 않습니다.")
                } else Note("설정된 ASR 및 AI 서비스에 발화가 전달될 수 있으며 비용이 발생할 수 있습니다.")
            }
            if (s.wakeActive || s.wakePending) Note("한국어 호출을 먼저 종료하세요.")
            else if (s.mediaCaption.busy) Note("미디어 자막을 먼저 종료하세요.")
            else if (s.captionActive) Note("실시간 자막을 먼저 종료하세요.")
            else if (s.translationActive) ToolButton(onClick = { model.attempt { model.service().stopTranslation() } }) { Text("번역 종료") }
            else ToolButton(onClick = { confirm = true }, enabled = linked && (!local || prepared) && !s.teleprompter.smartFollow && !s.teleprompter.busy && s.teleprompter.active == null) { Text("번역 시작") }
            if (!linked) Note("먼저 안경을 연결하세요.")
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("실시간 번역을 시작할까요?") },
        text = { Text(if (local) "로컬 음성 인식과 휴대폰 내 모델 서버를 사용합니다. 서버의 오프라인 설정을 확인하세요. 기존 기록을 중단할 수 있습니다. 참여자 동의와 장소 보안 규칙을 확인하세요." else "기존 기록 및 다른 화면을 중단할 수 있으며 음성 및 텍스트가 설정된 서비스로 전송될 수 있습니다. 참여자 동의와 이용량을 확인하세요.") },
        confirmButton = { TextButton(enabled = linked && (!local || prepared) && !s.translationActive && !s.teleprompter.smartFollow && !s.teleprompter.busy && s.teleprompter.active == null,
            onClick = { confirm = false; model.attempt { model.service().startTranslation(local) } }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
}

@Composable
internal fun DeviceOverview(model: AppModel, connect: () -> Unit, open: (String) -> Unit) {
    val s = model.state
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ContentCard {
            GlassesArtwork(Modifier.fillMaxWidth().height(90.dp), MaterialTheme.colorScheme.primary)
            Text(model.deviceName, style = MaterialTheme.typography.headlineSmall)
            Note(if (linked) "연결됨 · 휴대폰 직접 연결" else s.glasses.link.detail)
            ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text(if (linked) "연결 관리" else "안경 연결") }
        } }
        item { FeatureTile("안경 설정", "밝기 · 배터리 · 화면 위치 · 제스처 · 언어", Icons.Outlined.Settings, Modifier.fillMaxWidth()) { open("device-settings") } }
        item { FeatureTile("휴대폰 알림", "앱별 전달 · 본문 표시", Icons.Outlined.NotificationsNone, Modifier.fillMaxWidth()) { open("phone-notifications") } }
        item { FeatureTile("홈 화면 및 위젯", "레이아웃 · 콘텐츠", Icons.Outlined.Dashboard, Modifier.fillMaxWidth()) { open("content") } }
        item { FeatureTile("화면 및 오디오", "현재 표시 · 수신 상태 · 중지", Icons.Outlined.Visibility, Modifier.fillMaxWidth()) { open("display-controls") } }
        item { FeatureTile("한글 표시 검사", "내장 폰트 · 글리프 확인", Icons.Outlined.Visibility, Modifier.fillMaxWidth()) { open("korean-glyphs") } }
        item { FeatureTile("연결 문제 해결", "테스트 알림 · 연결 로그", Icons.Outlined.Tune, Modifier.fillMaxWidth()) { open("diagnostics") } }
    }
}

@Composable
internal fun CaptionToolScreen(model: AppModel, voiceSettings: () -> Unit) {
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
            Text("실시간 자막", style = MaterialTheme.typography.headlineSmall)
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
