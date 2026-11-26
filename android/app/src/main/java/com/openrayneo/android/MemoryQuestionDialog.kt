// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current.applicationContext
    val topics = remember(context) { runCatching { TopicStore(context).read() } }
    val profile = remember(context) { runCatching { AboutYouPolicy.shareableFields(AboutYouStore(context).read()) } }
    var topicId by remember { mutableStateOf<String?>(null) }
    var profileKeys by remember { mutableStateOf(setOf<String>()) }
    val pickedProfile = profile.getOrDefault(emptyMap()).filterKeys { it in profileKeys }
    val config = model.config
    val days = config.optString("memorySuggestionDays").toIntOrNull()?.takeIf { it in AutomaticMemoryPolicy.days } ?: 30
    val limit = config.optString("memorySuggestionLimit").toIntOrNull()?.takeIf { it in AutomaticMemoryPolicy.limits } ?: 3
    fun suggest() {
        busy = true; error = ""; result = null; selected = emptySet(); confirmed = false; suggested = true
        try {
            model.service().suggestQuestionMemory(question, topicId) { value ->
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
                Note("자동 추천 범위: 최근 ${days}일 · 최대 ${limit}개. 기기 내 구문·핵심어·일부 조사/띄어쓰기 변형 일치입니다. 의미 추론이나 외부 모델 호출은 하지 않습니다.")
                if (topics.isFailure) Note("주제 목록을 읽지 못했습니다. 전체 기록 검색만 가능합니다.")
                if (topics.getOrDefault(emptyList()).isNotEmpty()) {
                    Text("검색할 주제", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf(null to "전체") + topics.getOrDefault(emptyList()).map { it.id to it.title }).forEach { (id, title) ->
                            FilterChip(selected = topicId == id, onClick = {
                                topicId = id; result = null; selected = emptySet(); confirmed = false
                            }, label = { Text(title) }, enabled = !busy)
                        }
                    }
                    Note("선택한 주제에 직접 연결된 기록만 검색합니다. 주제 이름은 자동 전송하지 않습니다.")
                }
                ToolOutlinedButton(onClick = { suggest() }, enabled = !busy && question.isNotBlank() && !model.state.migration.busy) { Text("질문에 맞는 기억 추천") }
                OutlinedTextField(query, { query = it.take(160); result = null; selected = emptySet(); confirmed = false; error = "" },
                    Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("기억 검색어") })
                ToolOutlinedButton(onClick = {
                    busy = true; error = ""; result = null; selected = emptySet(); confirmed = false; suggested = false
                    try {
                        model.service().searchQuestionMemory(query, topicId) { value ->
                            busy = false
                            value.fold({ result = it }, { error = "기록 검색 실패 · 검색어를 확인하세요" })
                        }
                    } catch (_: Exception) { busy = false; error = "기록 검색을 시작하지 못했습니다" }
                }, enabled = !busy && query.isNotBlank() && !model.state.migration.busy) { Text("관련 기록 검색") }
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
                            Note("관련 후보 · 선택하기 전에 원문을 확인하세요")
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
                if (profile.isFailure) Note("개인화 정보를 읽지 못했습니다. 첨부하지 않습니다.")
                if (profile.getOrDefault(emptyMap()).isNotEmpty()) {
                    Text("나에 대해 · 이번 질문에만 첨부", style = MaterialTheme.typography.labelMedium)
                    profile.getOrDefault(emptyMap()).forEach { (key, value) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(key in profileKeys, { checked ->
                                profileKeys = if (checked) profileKeys + key else profileKeys - key
                                confirmed = false
                            }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(when (key) { "about" -> "소개"; "interests" -> "관심 주제"; else -> "답변 선호" })
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (selected.isNotEmpty() || pickedProfile.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(confirmed, { confirmed = it })
                        Text("선택한 전사 ${selected.size}개와 개인화 항목 ${pickedProfile.size}개 및 질문을 AI에 보내는 데 동의합니다.")
                    }
                    Note("설정된 DSH 모델 이용량이 발생할 수 있습니다. 휴대폰 일반 대화 목록에는 남기지 않지만 연결 서비스의 기록 정책은 별도입니다.")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && confirmed && (selected.isNotEmpty() || pickedProfile.isNotEmpty()) && question.isNotBlank(),
                onClick = {
                    val found = current ?: MemoryReferencePolicy.Result(emptyList(), false)
                    val chosen = found.copy(hits = found.hits.filterIndexed { index, _ -> index in selected }, more = false)
                    if (model.sendMemoryQuestion(question, chosen, pickedProfile)) dismiss()
                }) { Text("선택한 기억으로 질문") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
