// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutYouSection() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AboutYouStore(context) }
    val initial = remember(store) { runCatching { store.read() } }
    SectionLabel("나에 대해 · About You")
    Note("내가 직접 확정한 소개, 관심 주제, 답변 선호를 대화에서 자동 추출한 기억과 별도로 암호화 저장합니다.")
    if (initial.isFailure) {
        Text("개인화 정보를 읽지 못했습니다. 기존 정보는 덮어쓰지 않습니다.", color = MaterialTheme.colorScheme.error)
        return
    }
    var saved by remember(store) { mutableStateOf(initial.getOrThrow()) }
    var draft by remember(store) { mutableStateOf(saved) }
    var status by remember(store) { mutableStateOf("") }
    var failed by remember(store) { mutableStateOf(false) }
    var confirmClear by remember(store) { mutableStateOf(false) }
    val limit = AboutYouPolicy.MAX_FIELD_LENGTH
    val valid = listOf(draft.about, draft.interests, draft.preferences).all { it.trim().length <= limit }
    OpticalPanel {
        Text(if (draft != saved) "저장되지 않은 변경" else if (saved.hasContent) "저장된 개인화 정보" else "개인화 정보 없음", style = MaterialTheme.typography.titleMedium)
        Note("저장된 Agent 읽기 허용: " + if (saved.shareWithAgent) "허용" else "허용 안 함")
    }
    OutlinedTextField(draft.about, { draft = draft.copy(about = it); status = "" },
        Modifier.fillMaxWidth(), label = { Text("나에 대한 소개") }, supportingText = { Text("${draft.about.trim().length}/$limit") }, isError = draft.about.trim().length > limit)
    OutlinedTextField(draft.interests, { draft = draft.copy(interests = it); status = "" },
        Modifier.fillMaxWidth(), label = { Text("관심 주제 및 프로젝트") }, supportingText = { Text("${draft.interests.trim().length}/$limit") }, isError = draft.interests.trim().length > limit)
    OutlinedTextField(draft.preferences, { draft = draft.copy(preferences = it); status = "" },
        Modifier.fillMaxWidth(), label = { Text("답변 및 지원 방식 선호") }, supportingText = { Text("${draft.preferences.trim().length}/$limit") }, isError = draft.preferences.trim().length > limit)
    Row {
        Checkbox(draft.shareWithAgent, { draft = draft.copy(shareWithAgent = it); status = "" })
        Text("연결한 Agent가 개인화 정보를 필요할 때 읽도록 허용")
    }
    Note("초기값은 읽기 허용 안 함입니다. 허용해도 모든 발화에 자동 첨부하지 않습니다. 연결한 Agent가 읽으면 사용하는 모델에 전달될 수 있습니다. 업무 기밀이나 비밀번호는 입력하지 마세요. 아래 개인화 정보 저장 버튼을 눌러야 적용되며, 이미 전달된 정보는 철회되지 않습니다.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton(onClick = {
            runCatching { store.save(draft); AboutYouPolicy.validated(draft) }.fold({
                saved = it; draft = it; failed = false; status = "개인화 정보 저장 완료"
            }, { failed = true; status = "저장하지 못했습니다." })
        }, enabled = valid && draft != saved) { Text("개인화 정보 저장") }
        TextButton(onClick = { confirmClear = true }, enabled = saved.hasContent || draft.hasContent) { Text("정보 비우기") }
    }
    if (status.isNotBlank()) Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("개인화 정보를 비울까요?") },
        text = { Text("직접 입력한 소개·관심 주제·선호를 지우고 Agent 읽기 허용을 끕니다. 기존 대화 기록은 유지됩니다.") },
        confirmButton = { TextButton(onClick = {
            runCatching { store.save(AboutYouPolicy.Profile()) }.fold({
                saved = AboutYouPolicy.Profile(); draft = saved; failed = false; status = "개인화 정보를 비웠습니다."
            }, { failed = true; status = "개인화 정보를 비우지 못했습니다." })
            confirmClear = false
        }) { Text("비우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
}
