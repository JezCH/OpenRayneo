// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Separate opt-in for unattended attachment, accessible directly from ordinary chat. */
@Composable
internal fun OrdinaryContextButton() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { OrdinaryContextStore(context) }
    var open by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val current = remember(store, revision) { runCatching { store.read() } }
    TextButton(onClick = { open = true }) {
        Text(if (current.isFailure) "자동 기억 참조 · 읽기 오류"
            else if (current.getOrThrow().enabled) "자동 기억 참조 · 범위 설정됨" else "자동 기억 참조 · 꺼짐")
    }
    if (open) OrdinaryContextDialog(store) { open = false; revision++ }
}

@Composable
private fun OrdinaryContextDialog(store: OrdinaryContextStore, dismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val loaded = remember(store) { runCatching {
        Triple(store.read(), AboutYouPolicy.shareableFields(AboutYouStore(context).read()), TopicStore(context).read())
    } }
    if (loaded.isFailure) {
        AlertDialog(onDismissRequest = dismiss, title = { Text("자동 기억 참조") },
            text = { Note("기존 설정·프로필·주제를 읽을 수 없습니다. 자동 공유 범위를 덮어쓰지 않습니다.") },
            confirmButton = { TextButton(onClick = dismiss) { Text("닫기") } })
        return
    }
    val (grant, profile, topics) = loaded.getOrThrow()
    var fields by remember { mutableStateOf(OrdinaryContextPolicy.profile(grant, profile).keys.toSet()) }
    var ids by remember { mutableStateOf(grant.topicIds.intersect(topics.map { it.id }.toSet())) }
    var glasses by remember { mutableStateOf(grant.glasses) }
    var error by remember { mutableStateOf("") }
    val labels = mapOf("about" to "나에 대한 소개", "interests" to "관심 주제 및 프로젝트", "preferences" to "답변 및 지원 방식 선호")
    AlertDialog(onDismissRequest = dismiss, title = { Text("일반 질문 자동 기억 참조") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("한 번 허용한 항목만 다음 일반 질문에서 연결된 AI에 자동 첨부합니다. 기존 수동 첨부와는 별개이며 기본은 꺼짐입니다.")
            Text("개인화 정보", style = MaterialTheme.typography.titleSmall)
            if (profile.isEmpty()) Note("음성 및 기억 설정에서 About You 정보를 저장하고 읽기 허용을 먼저 켜세요.")
            profile.forEach { (key, value) ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(key in fields, { fields = if (it) fields + key else fields - key; error = "" })
                    Column(Modifier.weight(1f)) { Text(labels[key] ?: key); Note(value) }
                }
            }
            Text("자동 검색할 기록 주제", style = MaterialTheme.typography.titleSmall)
            if (topics.isEmpty()) Note("기억 화면에서 주제를 만들고 공유 가능한 기록을 직접 연결하세요.")
            topics.forEach { topic ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(topic.id in ids, { ids = if (it) ids + topic.id else ids - topic.id; error = "" })
                    Text(topic.title + " · " + topic.recordIds.size + "개 기록")
                }
            }
            Note("선택한 주제에 직접 연결한 기록만 대상으로 최근 30일의 완료된 안정 전사 중 관련 후보를 최대 3개 첨부합니다. 앞으로 해당 주제에 직접 연결하는 기록도 범위에 포함됩니다. 전체 기록을 자동 검색하지 않습니다.")
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(glasses, { glasses = it }, enabled = fields.isNotEmpty() || ids.isNotEmpty())
                Column(Modifier.weight(1f)) {
                    Text("안경의 명시적 일반 호출에도 사용")
                    Note("기본 꺼짐. 겹친 발화는 제외하지만 음성 호출은 본인 인증이 아닙니다. 주변인이 접근할 수 있는 상황에서는 끄세요.")
                }
            }
            Note("수동 첨부·선제 힌트·업무 실행·주변 대화에는 이 자동 범위를 사용하지 않습니다. 전사 및 프로필은 선택한 외부 AI에 전달될 수 있습니다. 이미 전달한 내용은 철회할 수 없으며 대화 이어가기를 별도로 켰다면 답변이 그 맥락에 남을 수 있습니다.")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { store.save(profile.filterKeys { it in fields }, ids, glasses) }
                .onSuccess { dismiss() }.onFailure { error = "저장 실패 · 개인화 정보와 주제를 다시 확인하세요" }
        }) { Text(if (fields.isEmpty() && ids.isEmpty()) "자동 참조 끄기" else "선택한 범위에 동의하고 저장") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
