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
                Note("AI: " + if (s.agentServer.enabled) "연결 기능 켜짐 · 실제 응답은 별도 확인" else "연결 설정 필요")
                if (c.running || s.alwaysOnResumePending) {
                    ToolButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !c.stopping,
                        modifier = Modifier.fillMaxWidth()) { Text(if (c.stopping) "저장 중…" else "기록 중지") }
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
                        Text("이번 기록만 연결 복구 시 자동 재개")
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
            FeatureTile("길 안내", "Amap 지원", Icons.Outlined.NearMe, Modifier.weight(1f)) { open("navigation") }
            FeatureTile("회의 기록", "기록 화면 열기", Icons.Outlined.GraphicEq, Modifier.weight(1f)) { navigate(2) }
        } }
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
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("한국어 ↔ 영어", style = MaterialTheme.typography.headlineSmall)
            Note(s.translationStatus)
            Note("음성 인식 및 번역 모델 설정이 필요합니다. 설정된 서비스에 발화가 전달될 수 있습니다.")
            if (s.translationActive) ToolButton(onClick = { model.attempt { model.service().stopTranslation() } }) { Text("번역 종료") }
            else ToolButton(onClick = { confirm = true }, enabled = linked && !s.teleprompter.smartFollow && !s.teleprompter.busy && s.teleprompter.active == null) { Text("번역 시작") }
            if (!linked) Note("먼저 안경을 연결하세요.")
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("실시간 번역을 시작할까요?") },
        text = { Text("실시간 번역은 기존 기록 및 다른 안경 화면을 중단할 수 있습니다. 음성 처리 및 모델 이용량이 발생할 수 있으므로 참여자 동의와 설정을 확인하세요.") },
        confirmButton = { TextButton(enabled = linked && !s.translationActive && !s.teleprompter.smartFollow && !s.teleprompter.busy && s.teleprompter.active == null,
            onClick = { confirm = false; model.attempt { model.service().startTranslation() } }) { Text("동의하고 시작") } },
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
        item { FeatureTile("홈 화면 및 위젯", "레이아웃 · 콘텐츠", Icons.Outlined.Dashboard, Modifier.fillMaxWidth()) { open("content") } }
        item { FeatureTile("화면 및 오디오", "현재 표시 · 수신 상태 · 중지", Icons.Outlined.Visibility, Modifier.fillMaxWidth()) { open("display-controls") } }
        item { FeatureTile("연결 문제 해결", "테스트 알림 · 연결 로그", Icons.Outlined.Tune, Modifier.fillMaxWidth()) { open("diagnostics") } }
    }
}
