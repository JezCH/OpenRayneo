// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelSelectionScreen(model: AppModel) {
    val config = model.config
    val selected = AiModelSelection.selected(config)
    val profiles = AiModelSelection.profiles(config)
    var editId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<AiModelSelection.Profile?>(null) }
    var legacy by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    fun edit(id: String) {
        val profile = profiles.firstOrNull { it.id == id }
        label = profile?.label ?: if (id == AiModelSelection.GPT) "GPT" else ""
        url = profile?.url ?: if (id == AiModelSelection.GPT) AiModelSelection.GPT_URL else ""
        name = profile?.model ?: if (id == AiModelSelection.GPT) AiModelSelection.GPT_DEFAULT else ""
        key = profile?.key.orEmpty()
        editId = id
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ContentCard {
            Text("현재 선택한 모델", style = MaterialTheme.typography.labelLarge)
            Text(AiModelSelection.label(config), style = MaterialTheme.typography.headlineSmall)
            Text(selectedAiStatus(model).label, style = MaterialTheme.typography.titleSmall)
            Note("안경·휴대폰 질문, AI 힌트, 기록 요약, 온라인 번역 및 후속 조치 분석에 같은 선택을 적용합니다. 내장 음성 인식·오프라인 번역은 그대로 유지합니다. 다른 모델로 자동 대체하지 않습니다.")
            if (!model.aiSwitchAllowed) Note("진행 중인 기록·번역·AI 작업을 마치면 모델을 변경할 수 있습니다.")
        }
        SectionLabel("구독 및 API 연결")
        val info = model.subscriptionInfo
        val plan = profiles.firstOrNull { it.id == AiModelSelection.CHATGPT_PLAN }
        ContentCard {
            Text("ChatGPT 구독 (별도 API Key 없음)", style = MaterialTheme.typography.titleMedium)
            if (selected == AiModelSelection.CHATGPT_PLAN) StatusPill("현재 선택한 연결 방식")
            Note("공식 ChatGPT 구독 추론 프리뷰입니다. 일반 채팅방·기억과 별개이며 공유 사용량 한도가 적용될 수 있습니다. 자동 유료 API 전환은 하지 않습니다.")
            if (model.subscriptionBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.subscriptionSigningIn) ToolOutlinedButton(onClick = { model.cancelSubscription() }) { Text("로그인 대기 취소") }
            } else {
                if (info.connected) {
                    Note("인증 저장됨 · 실제 모델 응답 확인 별도")
                    info.email?.let { Note("계정: " + it) }
                    ToolOutlinedButton(onClick = { model.refreshSubscriptionModels() }, enabled = model.aiSwitchAllowed) { Text("계정 모델 목록 갱신") }
                    info.models.forEach { option ->
                        val chosen = selected == AiModelSelection.CHATGPT_PLAN && plan?.model == option.id
                        ToolOutlinedButton(onClick = {
                            pending = AiModelSelection.Profile(AiModelSelection.CHATGPT_PLAN, "ChatGPT 구독",
                                AiModelSelection.GPT_URL, option.id, "")
                        }, enabled = model.aiSwitchAllowed && !chosen) { Text(option.name + if (chosen) " · 선택됨" else "") }
                    }
                    if (info.models.isEmpty()) Note("사용 가능한 모델 목록이 없습니다. 계정 권한을 확인하세요.")
                    ToolOutlinedButton(onClick = { revoke = true }, enabled = model.aiSwitchAllowed) { Text("구독 연결 해제") }
                } else {
                    ToolButton(onClick = { model.connectSubscription() }, enabled = model.aiSwitchAllowed) { Text("Continue with ChatGPT") }
                    if (info.registered) Note("기존 등록 계정" + (info.email?.let { ": " + it } ?: "") + "으로 다시 로그인합니다.")
                }
            }
        }
        val gpt = profiles.firstOrNull { it.id == AiModelSelection.GPT }
        ContentCard {
            Text("GPT API (별도 요금)", style = MaterialTheme.typography.titleMedium)
            if (selected == AiModelSelection.GPT) StatusPill("현재 선택한 연결 방식")
            Note(gpt?.let { it.model + if (selected == it.id) " · 선택됨" else " · 저장됨" }
                ?: "첫 연결에 사용자 API 인증 필요")
            Note("OpenAI API 연결입니다. ChatGPT 앱 로그인이나 구독이 자동으로 연결되지 않으며 API 이용료가 발생할 수 있습니다.")
            ToolButton(onClick = { if (gpt?.configured == true) model.selectAiModel(gpt.id) else edit(AiModelSelection.GPT) },
                enabled = model.aiSwitchAllowed && selected != AiModelSelection.GPT) { Text(if (selected == AiModelSelection.GPT) "선택됨" else "GPT 선택") }
            if (gpt != null) TextButton(onClick = { edit(gpt.id) }, enabled = model.aiSwitchAllowed) { Text("GPT 연결 설정") }
        }
        SectionLabel("제조사 및 기타 모델")
        ContentCard {
            Text("RayNeo AI", style = MaterialTheme.typography.titleMedium)
            StatusPill("선택 불가 · 독립 연결 검증 필요")
            Note("제조사 순정 AI · 공식 서비스 인증 및 독립 앱 연결 검증 필요. 준비 전에는 선택할 수 없으며 다른 모델로 대체하지 않습니다.")
        }
        profiles.filter { it.id != AiModelSelection.GPT && it.id != AiModelSelection.CHATGPT_PLAN }.forEach { profile ->
            ContentCard {
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                Note(profile.model + if (selected == profile.id) " · 선택됨" else " · 저장됨")
                ToolButton(onClick = { model.selectAiModel(profile.id) }, enabled = model.aiSwitchAllowed && profile.configured && selected != profile.id) {
                    Text(if (selected == profile.id) "선택됨" else "이 모델 선택")
                }
                TextButton(onClick = { edit(profile.id) }, enabled = model.aiSwitchAllowed) { Text("연결 설정") }
            }
        }
        ToolOutlinedButton(onClick = { edit("custom-" + java.util.UUID.randomUUID()) }, enabled = model.aiSwitchAllowed && profiles.size < 32) { Text("다른 AI 모델 추가") }
        SectionLabel("기존 연결 호환")
        ContentCard {
            Text("기존 연결 방식", style = MaterialTheme.typography.titleMedium)
            if (selected == AiModelSelection.LEGACY) StatusPill("현재 선택한 연결 방식")
            Note("기존 DSH Go·요약 설정 호환 모드입니다. 통합 모델 선택이 아니며 기존 사용자의 설정 보존을 위한 옵션입니다.")
            ToolOutlinedButton(onClick = { legacy = true }, enabled = model.aiSwitchAllowed && selected != AiModelSelection.LEGACY) { Text(if (selected == AiModelSelection.LEGACY) "선택됨" else "기존 방식 선택") }
        }
        Note("직접 연결 모델은 현재 질문·요약·온라인 번역을 지원합니다. 업무 실행 및 PC Codex는 동일 모델 경로가 구현될 때까지 기존 연결 방식에서만 사용할 수 있습니다.")
    }
    editId?.let { id ->
        val candidate = AiModelSelection.Profile(id, label.trim(), url.trim(), name.trim(), key.trim())
        AlertDialog(onDismissRequest = { editId = null; key = "" },
            title = { Text(if (id == AiModelSelection.GPT) "GPT 연결" else "AI 모델 추가·수정") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (id != AiModelSelection.GPT) {
                        OutlinedTextField(label, { label = it }, label = { Text("표시 이름") }, singleLine = true)
                        OutlinedTextField(url, { url = it }, label = { Text("Chat Completions 호환 서비스 주소") }, singleLine = true)
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("모델 이름") }, singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text("개인 API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Note("키는 휴대폰에 암호화 저장합니다. 원격 서비스는 HTTPS, 휴대폰 로컬 서버만 HTTP를 허용합니다. 저장 시 연결 테스트나 결제가 자동 실행되지는 않습니다.")
                }
            },
            confirmButton = { TextButton(enabled = candidate.label.isNotBlank() && candidate.configured && model.aiSwitchAllowed,
                onClick = { pending = candidate; editId = null; key = "" }) { Text("저장하고 선택") } },
            dismissButton = { TextButton(onClick = { editId = null; key = "" }) { Text("취소") } })
    }
    pending?.let { profile ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(profile.label + "로 변경") },
            text = { Text(if (profile.id == AiModelSelection.CHATGPT_PLAN) "이후 승인한 AI 요청은 ChatGPT 구독 사용량을 소비할 수 있습니다. 일반 채팅방·기억은 공유되지 않으며 별도 유료 API로 자동 전환하지 않습니다. 기존 기록과 자동 녹음 설정은 유지합니다." else "이후 앱에서 승인하여 사용하는 AI 기능의 질문·전사·참조 자료가 선택한 서비스에 전달될 수 있고 이용료가 발생할 수 있습니다. 기존 기록은 유지되며 자동 녹음이나 자동 AI 요청은 켜지지 않습니다.") },
            confirmButton = { TextButton(onClick = { pending = null; model.selectAiModel(profile.id, profile) }, enabled = model.aiSwitchAllowed) { Text("동의하고 선택") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } })
    }
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("ChatGPT 구독 연결 해제") },
        text = { Text("이 앱의 구독 인증을 종료합니다. ChatGPT 구독이나 기존 기록은 삭제하지 않습니다. 선택된 구독 모델은 재로그인 또는 다른 모델 선택 전까지 사용할 수 없습니다.") },
        confirmButton = { TextButton(onClick = { revoke = false; model.disconnectSubscription() }, enabled = model.aiSwitchAllowed) { Text("연결 해제") } },
        dismissButton = { TextButton(onClick = { revoke = false }) { Text("취소") } })
    if (legacy) AlertDialog(onDismissRequest = { legacy = false }, title = { Text("기존 연결 방식으로 변경") },
        text = { Text("저장된 기존 DSH Go 및 요약 모델 설정을 사용합니다. 두 경로가 동일한 모델이라는 보장은 없습니다.") },
        confirmButton = { TextButton(onClick = { legacy = false; model.selectAiModel(AiModelSelection.LEGACY) }, enabled = model.aiSwitchAllowed) { Text("변경") } },
        dismissButton = { TextButton(onClick = { legacy = false }) { Text("취소") } })
}
