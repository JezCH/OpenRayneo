// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local search first; only explicitly checked excerpts travel with a single question. */
@Composable
internal fun MemoryQuestionDialog(model: AppModel, initialQuestion: String, autoSuggest: Boolean = false, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var question by remember { mutableStateOf(initialQuestion.take(2000)) }
    var result by remember { mutableStateOf<MemoryReferencePolicy.Result?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var suggested by remember { mutableStateOf(autoSuggest) }
    val config = model.config
    val days = config.optString("memorySuggestionDays").toIntOrNull()?.takeIf { it in AutomaticMemoryPolicy.days } ?: 30
    val limit = config.optString("memorySuggestionLimit").toIntOrNull()?.takeIf { it in AutomaticMemoryPolicy.limits } ?: 3
    fun suggest() {
        busy = true; error = ""; result = null; selected = emptySet(); confirmed = false; suggested = true
        try {
            model.service().suggestQuestionMemory(question) { value ->
                busy = false
                value.fold({ result = it }, { error = "기억 추천 실패 · 직접 검색할 수 있습니다" })
            }
        } catch (_: Exception) { busy = false; error = "기억 추천을 시작하지 못했습니다" }
    }
    LaunchedEffect(autoSuggest) { if (autoSuggest && question.isNotBlank()) suggest() }
    val current = result
    val hits = current?.hits.orEmpty()
    val enabled = !busy && !model.state.phoneRunning && !model.state.migration.busy && model.state.agentServer.enabled
    AlertDialog(onDismissRequest = dismiss, title = { Text("기억을 참고한 질문") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Note("과거 기록을 휴대폰에서 먼저 검색합니다. 선택한 전사 일부와 질문만 연결된 AI로 전송합니다. 일반 AI 작업과 달리 도구를 사용하지 않습니다.")
                Note("자동 추천 범위: 최근 ${days}일 · 최대 ${limit}개. 추천은 기기 내 단어 일치 방식이며 실제 관련성을 보장하지 않습니다.")
                ToolOutlinedButton(onClick = { suggest() }, enabled = !busy && question.isNotBlank() && !model.state.migration.busy) { Text("질문에 맞는 기억 추천") }
                OutlinedTextField(query, { query = it.take(160); result = null; selected = emptySet(); confirmed = false; error = "" },
                    Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("기억 검색어") })
                ToolOutlinedButton(onClick = {
                    busy = true; error = ""; result = null; selected = emptySet(); confirmed = false; suggested = false
                    try {
                        model.service().searchQuestionMemory(query) { value ->
                            busy = false
                            value.fold({ result = it }, { error = "기록 검색 실패 · 검색어를 확인하세요" })
                        }
                    } catch (_: Exception) { busy = false; error = "기록 검색을 시작하지 못했습니다" }
                }, enabled = !busy && query.isNotBlank() && !model.state.migration.busy) { Text("내 기록 검색") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (current != null && hits.isEmpty()) Note("일치하는 전사 기록이 없습니다. 검색어를 바꿔 보거나 기억 없이 질문할 수 있습니다.")
                hits.forEachIndexed { index, hit ->
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(index in selected, { checked ->
                            selected = if (checked) selected + index else selected - index
                            confirmed = false
                        })
                        Column(Modifier.weight(1f)) {
                            Text(java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(hit.source.start)),
                                style = MaterialTheme.typography.labelMedium)
                            Text(hit.excerpt)
                            Note(if (hit.source.incomplete || hit.source.mixed || !hit.source.stable) "전사 또는 화자 정보가 불확실할 수 있음" else "원본 전사 · 사실 여부는 별도 확인")
                        }
                    }
                    HorizontalDivider()
                }
                if (current?.more == true) Note("추가 결과가 있습니다. 검색어를 구체화하면 더 정확히 선택할 수 있습니다.")
                OutlinedTextField(question, { question = it.take(2000); confirmed = false; if (suggested) { result = null; selected = emptySet() } }, Modifier.fillMaxWidth(),
                    label = { Text("AI에게 할 질문") }, enabled = !busy, minLines = 2, maxLines = 5)
                if (autoSuggest) TextButton(enabled = enabled && question.isNotBlank(), onClick = {
                    model.phoneDraft = question
                    model.send(AnswerSource.MOBILE_AGENT)
                    dismiss()
                }) { Text("기억 첨부 없이 일반 AI로 보내기") }
                if (selected.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(confirmed, { confirmed = it })
                        Text("선택한 전사 ${selected.size}개와 질문을 AI에 보내는 데 동의합니다.")
                    }
                    Note("설정된 DSH 모델 이용량이 발생할 수 있습니다. 휴대폰 일반 대화 목록에는 남기지 않지만 연결 서비스의 기록 정책은 별도입니다.")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && confirmed && selected.isNotEmpty() && question.isNotBlank() && current != null,
                onClick = {
                    val found = current ?: return@TextButton
                    val chosen = found.copy(hits = found.hits.filterIndexed { index, _ -> index in selected }, more = false)
                    if (model.sendMemoryQuestion(question, chosen)) dismiss()
                }) { Text("선택한 기억으로 질문") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
