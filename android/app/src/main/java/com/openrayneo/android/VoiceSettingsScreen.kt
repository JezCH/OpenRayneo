// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun VoiceSettingsScreen(model: AppModel, actions: UiActions) {
    val drafts = model.formDrafts
    val voiceKeys = listOf("asrKey", "asrUrl", "asrModel", "nativeAutoAnswer", "audioChannel", "speakerThreshold", "memoryPrompt", "memoryAutoSummary", "answerClearSeconds")
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("음성 및 기억 설정", style = MaterialTheme.typography.headlineSmall)
        Note("음성 특징과 화자 처리는 이 기기에서 실행되며 ASR과 기록 요약은 설정한 서비스를 사용합니다. 답변, 짧은 힌트, 작업 판단은 휴대폰 로컬 Agent가 맡고 키는 Android Keystore로 암호화 저장됩니다.")
        SectionLabel("Bailian ASR")
        VoiceField(model, "asrKey", "API Key", secret = true)
        VoiceField(model, "asrUrl", "WSS 주소", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")
        VoiceField(model, "asrModel", "모델", "qwen-audio-3.1-asr-flash-message")
        var auto by remember(model.config) { mutableStateOf(model.config.optBoolean("nativeAutoAnswer")) }
        Row { Checkbox(auto, { auto = it; drafts["nativeAutoAnswer"] = it.toString() }); Text("'샤오레이 샤오레이'로 깨우면 자동 응답") }
        Note("기본 깨우기로 응답한 뒤 오디오를 받아 ASR과 화자 처리를 마치고 다음으로 전달합니다: 로컬 Agent.")
        var autoMemory by remember(model.config) { mutableStateOf(model.config.optString("memoryAutoSummary", "true") != "false") }
        Row { Checkbox(autoMemory, { autoMemory = it; drafts["memoryAutoSummary"] = it.toString() }); Text("롤링·하루 마감·기록 종료 요약 자동 생성") }
        Note("종일 기록과 개별 기록은 원본 오디오를 자동 저장하고, 목소리 등록은 음성 특징만 저장합니다. 전사가 지연되면 오디오를 먼저 저장하고 복구 후 전사와 화자 분석을 보충합니다.")
        VoiceField(model, "answerClearSeconds", "실시간 힌트 답변 후 화면 지우기까지 초 (0이면 유지)", "0")
        SectionLabel("현재 위치 및 날씨")
        Note("로컬 Agent는 도구로 날씨를 조회할 수 있으며 위치 도구에는 위치 권한이 필요합니다. 백그라운드 음성은 안경 연결 포그라운드 서비스를 사용합니다.")
        TextButton(onClick = actions.locationPermission) { Text("위치 접근 허용") }
        SectionLabel("채널 및 식별 판단")
        Note("일반 2채널 동시 처리: 채널 1은 기본 본인, 채널 2는 별도 식별. 종일 기록과 기타 모노 오디오는 전체 화자 식별. 이 옵션은 직접 등록에만 쓰이며 기록 정책은 바뀌지 않습니다.")
        VoiceField(model, "audioChannel", "등록 진단 채널: 0/1 채널 1, 2 채널 2", "0")
        VoiceField(model, "speakerThreshold", "목소리 매칭 임계값 (내 목소리로 보정 필요)", "0.7")
        SectionLabel("기록 요약")
        VoiceField(model, "modelUrl", "요약 모델 서비스 주소")
        VoiceField(model, "modelName", "요약 모델 이름")
        VoiceField(model, "modelKey", "요약 모델 API Key", secret = true)
        StructuredVoicePrompt(model, "memoryPrompt", "기억 귀속 및 정리", ConversationPrompts.memory)
        Note("이 모델은 앱 내 기록 요약에만 사용됩니다. 원문, 화자, 서술 대상은 따로 저장되며 가져올 수 있는 모든 전사는 장기 보관됩니다.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ToolButton(onClick = {
            val invalid = listOf("speakerThreshold")
                .firstOrNull { key -> val value = drafts[key].orEmpty(); value.isNotBlank() && value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } != true }
            if (invalid != null) error = "$invalid 값은 0~1 사이여야 함"
            else if (drafts["audioChannel"].orEmpty().isNotBlank() && drafts["audioChannel"]?.toIntOrNull() !in 0..2) error = "채널은 0, 1, 2 중 하나여야 함"
            else if (drafts["answerClearSeconds"].orEmpty().isNotBlank() && drafts["answerClearSeconds"]?.toIntOrNull() !in 0..3600) error = "화면 지우기 지연은 0~3600초여야 함"
            else { error = null; model.saveConfig((voiceKeys + listOf("modelUrl", "modelName", "modelKey")).associateWith { drafts[it].orEmpty() }) }
        }, modifier = Modifier.fillMaxWidth()) { Text("음성 설정 저장") }
    }
}

@Composable
private fun VoiceField(model: AppModel, key: String, label: String, fallback: String = "", secret: Boolean = false) {
    val value = model.formDrafts[key].orEmpty()
    if (secret) OutlinedTextField(value, { model.formDrafts[key] = it }, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true)
    else OutlinedTextField(value, { model.formDrafts[key] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(fallback) }, singleLine = true)
}

@Composable
private fun StructuredVoicePrompt(model: AppModel, key: String, title: String, fallback: String) {
    val raw = model.formDrafts[key].orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val initial = remember(key, model.config) {
        if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject().put("objective", raw.ifBlank { fallback }).put("rules", JSONArray())
    }
    var role by remember(initial) { mutableStateOf(initial.optString("role")) }
    var objective by remember(initial) { mutableStateOf(initial.optString("objective")) }
    var rules by remember(initial) { mutableStateOf(initial.optJSONArray("rules")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }.orEmpty()) }
    fun update() {
        model.formDrafts[key] = JSONObject().put("role", role).put("objective", objective)
            .put("rules", JSONArray(rules.lines().map { it.trim() }.filter { it.isNotEmpty() })).toString()
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(title + if (expanded) " · 접기" else " · 편집") }
            if (expanded) {
                TextButton(onClick = { role = ""; objective = fallback; rules = ""; update() }) { Text("이 버전의 기본 프롬프트로 복원") }
                OutlinedTextField(role, { role = it; update() }, Modifier.fillMaxWidth(), label = { Text("역할") })
                OutlinedTextField(objective, { objective = it; update() }, Modifier.fillMaxWidth(), label = { Text("목표") }, minLines = 3)
                OutlinedTextField(rules, { rules = it; update() }, Modifier.fillMaxWidth(), label = { Text("규칙 (한 줄에 하나)") }, minLines = 3)
            }
        }
    }
}

@Composable
internal fun VoiceProfilesScreen(model: AppModel) {
    val state = model.state.conversation
    val self = state.people.firstOrNull { it.self }
    var rename by remember { mutableStateOf<VoicePerson?>(null) }
    var merge by remember { mutableStateOf<VoicePerson?>(null) }
    var history by remember { mutableStateOf<VoicePerson?>(null) }
    var split by remember { mutableStateOf<VoicePerson?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("목소리 및 인물", style = MaterialTheme.typography.headlineSmall)
        Note("선명한 목소리는 이름 없는 인물 프로필로 자동 생성되고 기록 간 연결을 시도합니다. 이름 지정, 병합, 분리 또는 과거 발화 선택으로 수정할 수 있으며 음성 특징은 휴대폰에만 저장됩니다. 본인 등록 시 혼자 서너 문장을 말하고 문장마다 잠깐 쉬어 주세요.")
        if (state.running && state.mode == ConversationMode.ENROLL) {
            var confirmed by remember { mutableStateOf(false) }
            Text("음성 ${state.enrollmentTotalSeconds.toInt()}초 · 일관 샘플 ${state.enrollmentSeconds.toInt()}초 · 수신 오디오 ${state.decodedSeconds.toInt()}초 ")
            if (state.enrollmentCanConfirm && !state.enrollmentReady) {
                Note("모델이 샘플 일관성을 의심합니다. 처음부터 끝까지 본인만 말했다면 이 샘플을 수동으로 표시할 수 있고, 다른 사람이 말했다면 다시 녹음하세요.")
                Row { Checkbox(confirmed, { confirmed = it }); Text("이 음성은 모두 본인이 말한 것임을 확인합니다") }
            }
            ToolButton(onClick = { model.service().conversations.saveEnrollment(self?.name ?: "나", true, self?.contactUri, confirmAllSamples = confirmed) }, enabled = !state.stopping && (state.enrollmentReady || state.enrollmentCanConfirm && confirmed)) { Text("내 목소리 저장") }
            TextButton(onClick = { model.service().conversations.stop() }) { Text("등록 중지") }
        } else ToolButton(onClick = { model.attempt { model.service().startConversation(ConversationMode.ENROLL, ConversationSource.REALTIME, self) } },
            enabled = !state.running && state.modelReady && model.state.glasses.link.phase == LinkPhase.FULL) { Text(if (self?.samples?.isNotEmpty() == true) "내 목소리 다시 등록" else "내 목소리 등록") }
        Note(state.status)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.people.filter { it.mergedInto == null }.forEach { person ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(person.name + if (person.self) " · 나" else "", style = MaterialTheme.typography.titleMedium)
                    if (person.unnamed) Note("자동 생성 · 이름 지정 대기")
                    Note(if (person.samples.isEmpty()) "등록된 목소리 없음" else "등록됨: ${person.samples.size}개 목소리 샘플")
                    if (person.contactUri != null) Note("선택한 연락처와 연결됨")
                    Row {
                        TextButton(onClick = { rename = person }) { Text("이름 지정") }
                        TextButton(onClick = { merge = person }, enabled = state.people.count { it.mergedInto == null } > 1) { Text("병합") }
                        TextButton(onClick = { split = person }) { Text("분리") }
                    }
                    TextButton(onClick = { history = person }) { Text("과거 발화 선택하여 수정") }
                    if (person.samples.isNotEmpty()) TextButton(onClick = { model.service().conversations.removeVoice(person.id) }) { Text("목소리 제거 (인물 기록 유지)") }
                }
            }
        }
    }
    rename?.let { person ->
        var name by remember(person.id) { mutableStateOf(person.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("인물 이름 지정") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("인물 이름") }, singleLine = true)
                Note("이 인물의 이름과 연결 표시를 업데이트합니다. 관련 요약과 Agent 결과는 새로 고침 필요로 표시됩니다.")
            }
        }, confirmButton = { TextButton(onClick = { model.attempt { model.service().conversations.renamePerson(person.id, name.trim()) }; rename = null }, enabled = name.isNotBlank()) { Text("이름 저장") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("취소") } })
    }
    merge?.let { PersonMergeDialog(model, it) { merge = null } }
    history?.let { HistoricalSpeakerDialog(model, it.id, dismiss = { history = null }) }
    split?.let { HistoricalSpeakerDialog(model, it.id, split = true, dismiss = { split = null }) }
}

@Composable
private fun PersonMergeDialog(model: AppModel, initial: VoicePerson, dismiss: () -> Unit) {
    val state = model.state.conversation
    val people = state.people.filter { it.mergedInto == null }
    var sourceIds by remember { mutableStateOf(setOf(initial.id)) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var recordIds by remember { mutableStateOf(emptySet<String>()) }
    val records = state.records.filter { record -> record.segments.any { it.personId in sourceIds } }
    val validRecords = records.filter { it.id in recordIds }
    val affected = validRecords.sumOf { record -> record.segments.count { it.personId in sourceIds } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("인물 프로필 병합") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("병합할 프로필", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id != targetId }.forEach { person ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(person.id in sourceIds, { checked -> sourceIds = if (checked) sourceIds + person.id else sourceIds - person.id })
                    Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("병합 대상", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id !in sourceIds }.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { targetId = person.id }) {
                    RadioButton(targetId == person.id, { targetId = person.id }); Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("과거 귀속 수정 범위", style = MaterialTheme.typography.titleSmall)
            records.forEach { record ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(record.id in recordIds, { checked -> recordIds = if (checked) recordIds + record.id else recordIds - record.id })
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(record.title)
                        Note(java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.CHINA).format(java.util.Date(record.createdAt)))
                    }
                }
            }
            Note("병합할 목소리 프로필 ${sourceIds.size}개, 수정할 선택 기록 ${validRecords.size}개, $affected개 발화. 관련 요약과 Agent 결과는 새로 고침 필요로 표시됩니다. 선택하지 않은 과거 원문은 현재 귀속을 유지합니다.")
        }
    }, confirmButton = { TextButton(onClick = {
        model.attempt { model.service().conversations.mergePeople(sourceIds, requireNotNull(targetId), validRecords.map { it.id }.toSet()) }
        dismiss()
    }, enabled = sourceIds.isNotEmpty() && targetId != null && targetId !in sourceIds) { Text("병합 확인") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
