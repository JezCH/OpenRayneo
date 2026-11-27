// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowUpSection(model: AppModel, record: ConversationRecord) {
    val decoded = remember(record.derived) { runCatching { FollowUpRecords.rows(record) } }
    SectionLabel("후속 조치 · 검토 후 등록")
    Note("선택한 기록의 원문 구간만 선택한 AI에 보내 후보를 추출합니다. 후보는 실제 업무나 약속이 아닙니다. 날짜·시간은 직접 입력하며 확인 버튼을 누르기 전에는 안경에 등록하지 않습니다.")
    if (decoded.isFailure) { Note("후속 조치 정보를 읽지 못했습니다. 기존 정보는 유지됩니다."); return }
    val rows = decoded.getOrThrow()
    val pages = (record.segments.size + FollowUpPolicy.PAGE_SIZE - 1) / FollowUpPolicy.PAGE_SIZE
    var page by remember(record.id, record.revision) { mutableIntStateOf(0) }
    var busy by remember(record.id) { mutableStateOf(false) }
    var message by remember(record.id) { mutableStateOf("") }
    var reviewing by remember(record.id) { mutableStateOf<FollowUpRecords.Row?>(null) }
    var dismissing by remember(record.id) { mutableStateOf<FollowUpRecords.Row?>(null) }
    val ready = model.state.available && !model.state.conversation.running
    val canExtract = ready && model.aiReady && !record.audioPending && !record.processingPending && !busy && pages > 0
    var confirmExtract by remember(record.id) { mutableStateOf(false) }
    val visibleRows = rows.filter { it.status != FollowUpPolicy.Status.DISMISSED }
    val linkReady = ready && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting
    if (record.transcriptIncomplete) Note("누락된 전사가 있는 기록입니다. 후보가 전체 업무를 포괄하지 않을 수 있습니다.")
    OpticalPanel {
        Text("후속 조치 현황", style = MaterialTheme.typography.titleMedium)
        Note("검토 대기 ${visibleRows.count { it.status == FollowUpPolicy.Status.PENDING }}개 · 기기 확인 ${visibleRows.count { it.status == FollowUpPolicy.Status.CONFIRMED }}개 · 결과 미확인 ${visibleRows.count { it.status == FollowUpPolicy.Status.ATTEMPTED || it.status == FollowUpPolicy.Status.UNKNOWN }}개")
        Note("후보 추출 AI: " + AiModelSelection.label(model.config))
        if (!model.aiReady) Note("AI 모델 연결을 먼저 확인하세요.")
        if (record.audioPending || record.processingPending) Note("전사 보충이 완료된 뒤 후보를 추출할 수 있습니다.")
    }
    if (pages > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ page-- }, enabled = !busy && page > 0) { Text("이전") }
            Text("원문 구간 ${page + 1}/$pages")
            TextButton({ page++ }, enabled = !busy && page + 1 < pages) { Text("다음") }
        }
        ToolButton(onClick = { confirmExtract = true }, enabled = canExtract, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "추출 중…" else "이 구간에서 후보 추출")
        }
        Note("구간당 원문 최대 20개(각 400자까지), 후보 최대 5개. 긴 발화의 일부는 모델 입력에서 잘릴 수 있습니다. 필요한 구간을 각각 선택하세요.")
    }
    if (message.isNotBlank()) Note(message)
    if (confirmExtract) AlertDialog(onDismissRequest = { confirmExtract = false },
        title = { Text("원문을 AI로 분석할까요?") },
        text = { Text("선택한 구간 ${page + 1}/$pages 의 원문을 " + AiModelSelection.label(model.config) + "에 전달합니다. 이용량 또는 비용이 발생할 수 있습니다. 후보만 생성하며 실제 일정·할 일은 등록하지 않습니다.") },
        confirmButton = { TextButton(enabled = canExtract, onClick = {
            confirmExtract = false
            try {
                busy = true; message = "선택한 원문에서 후보 추출 중"
                model.service().extractFollowUps(record.id, record.revision, page) { result ->
                    busy = false
                    message = result.fold({ "새 후보 ${it}개 저장. 아래에서 원문을 검토하세요." }, { it.message ?: "후보 추출 실패" })
                }
            } catch (error: Exception) { busy = false; message = error.message ?: "후보 추출 실패" }
        }) { Text("동의하고 추출") } },
        dismissButton = { TextButton(onClick = { confirmExtract = false }) { Text("취소") } })
    for (row in visibleRows) {
        val source = record.segments.firstOrNull { it.id == row.candidate.segmentId }
        val stale = row.item.stale || row.item.sourceRevision != record.revision || source == null
        ContentCard {
            Text(row.approved?.title ?: row.candidate.title, style = MaterialTheme.typography.titleSmall)
            Note(if (row.candidate.kind == FollowUpPolicy.Kind.TODO) "할 일 후보" else "일정 후보")
            if (source != null) Note("근거 전사: " + source.text)
            if (stale) Note("원본이 변경되었습니다. 등록 전 새 후보를 추출하세요.")
            row.approved?.let { approval ->
                if (approval.start != null && approval.end != null) Note("승인한 시각: ${Instant.ofEpochSecond(approval.start).atZone(ZoneId.systemDefault())} ~ ${Instant.ofEpochSecond(approval.end).atZone(ZoneId.systemDefault())}")
            }
            when (row.status) {
                FollowUpPolicy.Status.PENDING -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ reviewing = row }, enabled = linkReady && !stale) { Text("검토 및 등록") }
                    TextButton({ dismissing = row }) { Text("제외") }
                }
                FollowUpPolicy.Status.CONFIRMED -> Note("기기 재조회로 등록 확인 · ID ${row.eventId}")
                FollowUpPolicy.Status.ATTEMPTED, FollowUpPolicy.Status.UNKNOWN -> Note("등록 시도됨 · 결과 미확인. 안경의 할 일/일정 목록에서 확인하세요. 중복 방지를 위해 자동 재등록하지 않습니다.")
                FollowUpPolicy.Status.DISMISSED -> Unit
            }
        }
    }
    reviewing?.let { row ->
        var title by remember(row.item.id) { mutableStateOf(row.candidate.title) }
        var start by remember(row.item.id) { mutableStateOf("") }
        var end by remember(row.item.id) { mutableStateOf("") }
        var error by remember(row.item.id) { mutableStateOf("") }
        val zone = ZoneId.systemDefault()
        AlertDialog(onDismissRequest = { reviewing = null }, title = { Text("안경에 실제 등록할까요?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("등록 제목") }, singleLine = true)
                    if (row.candidate.kind == FollowUpPolicy.Kind.SCHEDULE) {
                        Note("직접 확인한 시작/종료 시각을 입력하세요. 시간대: ${zone.id}")
                        OutlinedTextField(start, { start = it }, label = { Text("시작 YYYY-MM-DD HH:mm") }, singleLine = true)
                        OutlinedTextField(end, { end = it }, label = { Text("종료 YYYY-MM-DD HH:mm") }, singleLine = true)
                    }
                    Note("이 항목만 현재 연결된 안경에 등록합니다. 원문 전사는 사실을 보장하지 않습니다.")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val approval = FollowUpPolicy.approval(row.candidate.kind, title, start, end, zone)
                    model.service().approveFollowUp(record.id, record.revision, row, approval) { result ->
                        message = result.fold({ if (it == FollowUpPolicy.Status.CONFIRMED) "기기 재조회로 등록 확인" else "결과 미확인: 안경 목록을 확인하세요" }, { it.message ?: "등록 결과 저장 실패" })
                    }
                    reviewing = null
                } catch (failure: Exception) { error = failure.message ?: "등록 요청 실패" }
            }, enabled = linkReady) { Text("확인하고 등록") } },
            dismissButton = { TextButton({ reviewing = null }) { Text("취소") } })
    }
    dismissing?.let { row ->
        AlertDialog(onDismissRequest = { dismissing = null }, title = { Text("이 후보를 제외할까요?") },
            text = { Text("이 후보만 제외합니다. 원본 기록이나 기존 할 일은 삭제하지 않습니다.") },
            confirmButton = { TextButton({
                try { model.service().dismissFollowUp(record.id, row); dismissing = null }
                catch (failure: Exception) { message = failure.message ?: "후보 제외 실패"; dismissing = null }
            }) { Text("제외") } }, dismissButton = { TextButton({ dismissing = null }) { Text("취소") } })
    }
}
