// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun RecordMigrationScreen(model: AppModel) {
    val state = model.state.migration
    var port by remember { mutableStateOf("8767") }
    var token by remember { mutableStateOf("") }
    var includeAudio by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val autoSummary = SummaryDispatchPolicy.autoEnabled(model.config.optString("memoryAutoSummary"))
    val validPort = port.toIntOrNull()?.takeIf { it in 1024..65535 }
    val credential = token.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Authorization: Bearer ", true) }
        ?.substringAfter("Bearer ", "")?.trim() ?: token.trim()
    val ready = validPort != null && credential.length in 16..256 && credential.all { it.code in 33..126 }
    val available = model.state.available
    val targetSync = model.state.recordSync.enabled
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("1. 원본 앱 연결", style = MaterialTheme.typography.titleLarge)
            Note("기존 앱을 삭제하거나 초기화하지 마세요. 같은 휴대폰에 서로 다른 패키지로 설치된 OpenRayneo 사이에서만 사용할 수 있습니다.")
            Note("기존 앱에서 녹음과 편집을 종료하고, 설정 → DSH Go 및 외부 Agent → 기록 동기화를 켠 후 전용 토큰을 복사하세요. 신뢰할 수 있는 네트워크에서만 진행하고 이전 후 기존 동기화를 끄세요.")
            Note("새 앱의 기록 동기화는 꺼져 있어야 합니다. 이전은 휴대폰 내부 주소 127.0.0.1로만 연결합니다.")
            if (!available) Note("서비스 상태 확인 대기")
            if (targetSync) ToolOutlinedButton(onClick = { model.attempt { model.service().recordSync.stop() } }) { Text("새 앱 동기화 끄기") }
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
                enabled = !state.busy, label = { Text("기존 앱 동기화 포트") }, singleLine = true)
            OutlinedTextField(token, { token = it.take(1024) }, Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text("기록 동기화 전용 토큰 또는 복사한 정보") },
                visualTransformation = PasswordVisualTransformation())
            Note("토큰은 이번 이전 과정에만 사용하며 설정이나 파일에 저장하지 않습니다.")
            ToolButton(onClick = {
                val p = validPort ?: return@ToolButton
                model.attempt { model.service().previewMigration(p, credential) }
                token = ""
            }, enabled = available && !state.busy && ready && !targetSync, modifier = Modifier.fillMaxWidth()) { Text("이전 가능 항목 확인") }
        } }
        item { ContentCard {
            Text(if (state.busy) "이전 처리 중" else "2. 이전 범위 및 결과", style = MaterialTheme.typography.titleLarge)
            Note(state.status)
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Note("완료·실패 수는 실제 처리 결과입니다. 전체 진행률은 확인되지 않습니다.")
            }
            if (state.prepared || state.busy || state.records > 0 || state.people > 0) {
                Text("대상 기록 ${state.records}개 · 인물 ${state.people}명")
                Note("원본 음성 약 ${state.audioBytes / 1_048_576} MiB · 제외/중복 ${state.skipped}개")
                Text("저장 완료 기록 ${state.importedRecords}개 · 인물 ${state.importedPeople}명 · 실패 ${state.failed}개")
            }
            if (state.prepared) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeAudio, { includeAudio = it })
                    Text("원본 음성도 함께 가져오기")
                }
                Note("끄면 전사·기억·요약만 이전합니다. 동일 ID의 기록과 인물은 덮어쓰지 않습니다. 미완료 기록은 제외합니다.")
                if (autoSummary) {
                    Note("기록을 옮기는 동안 자동 AI 요약이 실행되지 않도록 먼저 끄세요. 이전이 끝나면 직접 다시 켤 수 있습니다.")
                    ToolOutlinedButton(onClick = { model.saveConfig(mapOf("memoryAutoSummary" to "false")) }) { Text("자동 요약 끄기") }
                }
                ToolButton(onClick = { confirm = true }, enabled = available && !state.busy && !autoSummary && !targetSync, modifier = Modifier.fillMaxWidth()) { Text("선택 범위 가져오기") }
                TextButton(onClick = { model.attempt { model.service().migration.cancel() } }) { Text("이전 목록 지우기") }
            } else if (state.busy) ToolOutlinedButton(onClick = { model.attempt { model.service().migration.cancel() } }) { Text("이전 중지") }
            Note("음성 지문(화자 학습 데이터), 개인 설정, 토픽/프로필, 연결 키, 외부 서비스 로그인은 이 이전 경로로 옮기지 않습니다. 일부 실패 시 원본은 그대로 두고 다시 시도하세요.")
        } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("기존 기록을 가져올까요?") },
        text = { Text("기존 앱에서 읽기만 하고 새 앱에 암호화하여 저장합니다. " +
            if (includeAudio) "원본 음성도 옮깁니다. 저장공간을 확인하세요." else "원본 음성을 제외하고 전사·기억·요약만 옮깁니다.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.attempt { model.service().transferMigration(includeAudio) } },
            enabled = available && state.prepared && !state.busy && !autoSummary && !targetSync) { Text("가져오기") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
}
