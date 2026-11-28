// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID

internal class TopicUiState(private val store: TopicStore) {
    var topics by mutableStateOf<List<TopicPolicy.Topic>>(emptyList())
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    init {
        runCatching { store.read() }.fold({
            topics = it; ready = true
        }, { error = "주제를 읽지 못했습니다. 기존 정보는 유지됩니다." })
    }
    fun update(change: (List<TopicPolicy.Topic>) -> List<TopicPolicy.Topic>): Boolean {
        if (!ready) return false
        return runCatching { store.update(change) }.fold({
            topics = it; error = ""; true
        }, {
            error = it.message ?: "주제를 저장하지 못했습니다"; false
        })
    }
}

@Composable
internal fun rememberTopicState(): TopicUiState {
    val context = LocalContext.current.applicationContext
    return remember(context) { TopicUiState(TopicStore(context)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TopicPanel(state: TopicUiState, selected: String?, select: (String?) -> Unit) {
    SectionLabel("주제별 기록 · Topics")
    Note("주제는 기존 기록의 연결 모음입니다. 한 기록을 여러 주제에 넣을 수 있습니다.")
    if (!state.ready) {
        Note(state.error)
        return
    }
    val current = state.topics.firstOrNull { it.id == selected }
    var edit by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<TopicPolicy.Topic?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(current == null, { select(null) }, label = { Text("날짜별 전체") })
        state.topics.forEach { topic ->
            FilterChip(current?.id == topic.id, { select(topic.id) }, label = { Text(topic.title) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { editingId = null; title = ""; edit = true },
            enabled = state.topics.size < TopicPolicy.MAX_TOPICS) { Text("새 주제") }
        if (current != null) {
            TextButton(onClick = { editingId = current.id; title = current.title; edit = true }) { Text("이름 변경") }
            TextButton(onClick = { delete = current }) { Text("주제 삭제") }
        }
    }
    if (state.error.isNotBlank()) Note(state.error)
    if (edit) AlertDialog(onDismissRequest = { edit = false },
        title = { Text(if (editingId == null) "새 주제" else "주제 이름 변경") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("주제 이름") }, singleLine = true)
                if (state.error.isNotBlank()) Note(state.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = editingId ?: UUID.randomUUID().toString()
                if (state.update { if (editingId == null) TopicPolicy.create(it, id, title) else TopicPolicy.rename(it, id, title) }) {
                    select(id); edit = false
                }
            }, enabled = title.trim().length in 1..TopicPolicy.MAX_TITLE) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = { edit = false }) { Text("취소") } })
    delete?.let { topic ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("주제를 삭제할까요?") },
            text = { Text("'${topic.title}' 주제의 연결 ${topic.recordIds.size}개만 지웁니다. 원래 기록과 음성, 다른 주제는 유지됩니다.") },
            confirmButton = { TextButton(onClick = {
                if (state.update { TopicPolicy.remove(it, topic.id) }) { select(null); delete = null }
            }) { Text("주제 삭제") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("취소") } })
    }
}

@Composable
internal fun TopicMembership(state: TopicUiState, recordId: String) {
    SectionLabel("연결된 주제")
    if (!state.ready) {
        Note(state.error)
        return
    }
    if (state.topics.isEmpty()) {
        Note("기록 목록에서 새 주제를 먼저 만들어 주세요.")
        return
    }
    Note("주제를 누르면 이 기록의 연결만 추가하거나 해제합니다.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.topics.forEach { topic ->
            val linked = recordId in topic.recordIds
            FilterChip(linked, { state.update { TopicPolicy.link(it, topic.id, recordId, !linked) } },
                label = { Text(topic.title) })
        }
    }
    if (state.error.isNotBlank()) Note(state.error)
}
