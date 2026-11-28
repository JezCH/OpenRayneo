// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationScreen(model: AppModel, actions: UiActions, open: (String) -> Unit) {
    val state = model.state.conversation
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val topicState = rememberTopicState()
    var topicId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenTopic = topicState.topics.firstOrNull { it.id == topicId }
    var liveSpeaker by remember { mutableStateOf<SpeechSegment?>(null) }
    LaunchedEffect(state.activeRecord?.id) { liveSpeaker = null }
    val recordListState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchAllDates by rememberSaveable { mutableStateOf(true) }
    val searchQuery = query.trim()
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val openAudio = state.records.any { it.audioPending }
    LaunchedEffect(state.running, openAudio) {
        now = System.currentTimeMillis()
        while (state.running || openAudio) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val date = LocalDate.parse(dateText)
    val record = state.records.firstOrNull { it.id == selected }
    if (record != null) {
        androidx.activity.compose.BackHandler { selected = null }
        ConversationDetail(model, record, actions, topicState) { selected = null }
        return
    }
    liveSpeaker?.let { segment -> state.activeRecord?.let { active ->
        SpeakerBindingDialog(model, active, segment, actions) { liveSpeaker = null }
    } }
    var source by rememberSaveable { mutableStateOf(ConversationSource.ALWAYS_ON) }
    var resumeOnReconnect by rememberSaveable { mutableStateOf(false) }
    val s = model.state
    val peopleById = remember(state.people) { state.people.associateBy { it.id } }
    val matchingRecords = remember(state.records, peopleById, searchQuery) {
        if (searchQuery.isBlank()) state.records
        else state.records.filter { RecordSearchPolicy.matches(it, peopleById, searchQuery) }
    }
    val listScope = rememberCoroutineScope()
    val savedRecordsIndex = 1 + (if (state.lastHint.isNotBlank()) 1 else 0) +
        (if (state.running && state.segments.isNotEmpty()) 1 + minOf(12, state.segments.size) else 0)
    val linked = s.available && s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val startReady = linked && state.modelReady && model.recommendedEditable &&
        !state.running && !state.stopping && !s.alwaysOnResumePending &&
        !s.captionActive && !s.mediaCaption.busy && !s.glasses.navigation.enabled && source != ConversationSource.NATIVE
    var pendingStart by remember { mutableStateOf<ConversationMode?>(null) }
    if (pendingStart != null) AlertDialog(onDismissRequest = { pendingStart = null },
        title = { Text("기록을 시작할까요?") },
        text = { Text("안경 마이크를 인식하고 기록합니다. 보존 설정에 따라 원본 음성이 저장될 수 있고 설정한 AI 서비스에 전사가 전달될 수 있습니다. 참여자 동의와 장소 보안 규칙을 확인하세요.") },
        confirmButton = { TextButton(enabled = startReady, onClick = {
            val mode = pendingStart
            pendingStart = null
            if (mode != null) model.attempt {
                model.service().startConversation(mode, source, resumeOnReconnect = mode == ConversationMode.DAILY &&
                    source == ConversationSource.ALWAYS_ON && resumeOnReconnect)
                resumeOnReconnect = false
            }
        }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { pendingStart = null }) { Text("취소") } })
    LazyColumn(state = recordListState, modifier = Modifier.imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            OpticalHeroPanel {
                OpticalWorkspaceHeading(if (state.running || state.stopping || s.alwaysOnResumePending) "현재 기록" else "새 기록", "기억 및 음성 기록", 2)
                Text(recording(s).label, style = MaterialTheme.typography.labelLarge,
                    color = if (recording(s).phase == SystemStatusPolicy.Phase.ACTIVE) OpticalActiveInk() else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!s.available) Note("서비스 상태를 확인하고 있습니다.")
                if (!state.running && !state.stopping && !s.alwaysOnResumePending)
                    Note("일상과 회의를 기록합니다. 참여자 동의와 장소의 보안 규칙을 먼저 확인하세요.")
                if (model.state.alwaysOnResumePending) {
                    Note("안경 연결이 끊겼습니다. 이번에 허용한 종일 일상 기록은 이전 기록 저장이 끝나고 같은 안경에 자동 재연결되면 이탈 시간과 관계없이 다시 시작합니다. 직접 중지하거나 서비스가 종료되면 재개하지 않습니다.")
                    ToolButton(onClick = { model.attempt { model.service().stopConversation() } }) { Text("자동 재개 취소 및 기록 중지") }
                } else if (!state.running && !state.stopping) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversationSource.entries.forEach { option -> FilterChip(source == option, { source = option }, label = { Text(when (option) { ConversationSource.ALWAYS_ON -> "종일 기록"; ConversationSource.REALTIME -> "실시간 힌트"; ConversationSource.NATIVE -> "음성 깨우기" }) }) }
                    }
                    if (source == ConversationSource.ALWAYS_ON) {
                        Row {
                            Checkbox(resumeOnReconnect, { resumeOnReconnect = it })
                            Text("이번 종일 일상 기록은 연결 복구 시 자동 재개")
                        }
                        Note("이 동의는 이번 일상 기록과 자동 재연결에만 적용됩니다. 원본 음성이 저장될 수 있으므로 참여자의 동의 및 장소의 보안 정책을 확인하세요. 앱 서비스 중지 또는 재부팅 후에는 자동으로 시작하지 않습니다.")
                    }
                    if (source == ConversationSource.NATIVE) {
                        Note("기본 음성 깨우기는 안경의 실제 호출 이벤트로 시작합니다. 여기서 기록 시작 버튼을 누르는 방식은 지원하지 않습니다.")
                        TextButton(onClick = { open("korean-wake") }) { Text("기본 호출 설정 및 진단") }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolButton(onClick = { pendingStart = ConversationMode.DAILY }, enabled = startReady, modifier = Modifier.fillMaxWidth()) { Text("일상 기록 시작") }
                            ToolOutlinedButton(onClick = { pendingStart = ConversationMode.MEETING }, enabled = startReady, modifier = Modifier.fillMaxWidth()) { Text("회의 기록 시작") }
                        }
                        if (!startReady) Note(when {
                            !linked -> "안경을 완전히 연결하세요."
                            !state.modelReady -> "내장 음성 모델 상태를 확인하세요."
                            else -> "현재 연결된 화면·음성·AI 또는 기록 이전 작업을 종료하세요."
                        })
                        if (!linked) TextButton(onClick = { open("display-controls") }) { Text("안경 연결 관리") }
                        else if (!state.modelReady) TextButton(onClick = { open("voice-settings") }) { Text("음성 상태 확인") }
                    }
                } else {
                    Text(if (state.reprocessing) "저장된 원본 재전사 중 · 새 녹음 아님" else recording(s).label + " · " + state.mode.title + " · 오디오 ${state.decodedSeconds.toInt()}초 수신")
                    if (state.activeRecord?.transcriptOnly == true) Note("기기 내장 전사 · 원본 음성 미보존")
                    if (state.voiceGate.isNotEmpty()) Text(state.voiceGate)
                    ToolButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !state.stopping, modifier = Modifier.fillMaxWidth()) { Text(if (state.stopping) "마무리 중…" else if (state.reprocessing) "재전사 중지" else "기록 및 음성 수신 중지") }
                }
                Note(state.status)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.modelReady) Note("기록·화자 처리 엔진 초기화 대기 중입니다. 오류가 있으면 위 내용을 확인하세요.")
                else if (!model.speechConfigured) Note("선택한 음성 인식 설정이 완료되지 않았습니다. 원본 보존 모드에서만 전사 보충이 가능할 수 있습니다.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open("voice-settings") }) { Text("음성 설정") }
                    TextButton(onClick = { open("voice-profiles") }) { Text("인물 관리") }
                    TextButton(onClick = { listScope.launch { recordListState.scrollToItem(savedRecordsIndex) } }) { Text("저장된 기록 찾기") }
                }
            }
        }
        if (state.lastHint.isNotBlank()) item {
            ContentCard { Text("최근 짧은 힌트", style = MaterialTheme.typography.titleMedium); Text(state.lastHint) }
        }
        if (state.running && state.segments.isNotEmpty()) {
            item { SectionLabel("현재 대화") }
            items(state.segments.takeLast(12), key = { it.id }) { segment -> SpeechRow(segment, state.people, state.activeRecord) { liveSpeaker = segment } }
        }
        item(key = "record-search") {
            SectionLabel("저장된 기록")
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text("원문, 인물, 기억 검색") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "검색어 지우기") }
                },
                singleLine = true,
            )
            if (searchQuery.isNotBlank() && chosenTopic == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(searchAllDates, { searchAllDates = true }, label = { Text("전체 기간") })
                FilterChip(!searchAllDates, { searchAllDates = false }, label = { Text("선택한 기간") })
            }
        }
        item { TopicPanel(topicState, topicId) { topicId = it } }
        if (chosenTopic != null) {
            val linkedRecords = matchingRecords.filter { it.id in chosenTopic.recordIds }.sortedByDescending { it.createdAt }
            item {
                SectionLabel("${chosenTopic.title} · 전체 기간 · ${linkedRecords.size}개 기록")
                if (linkedRecords.isEmpty()) Note(if (searchQuery.isBlank()) "날짜별 전체에서 기록을 열어 이 주제를 연결하세요." else "이 주제에서 일치하는 기록이 없습니다.")
                if (state.records.count { it.id in chosenTopic.recordIds } < chosenTopic.recordIds.size) Note("현재 기기에 없는 기록 연결은 표시하지 않습니다.")
            }
            items(linkedRecords, key = { "topic:${it.id}" }) { row ->
                Card(onClick = { selected = row.id }, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Note("${row.source.title} · ${recordTime(row)} · ${row.segments.size}개 원문")
                        if (row.transcriptIncomplete) Note("전사 누락 구간 있음")
                        if (searchQuery.isNotBlank()) Note(RecordSearchPolicy.excerpt(row, peopleById, searchQuery))
                        else if (row.summary.isNotBlank() && !row.summaryStale) Text(row.summary.take(160))
                        else row.segments.firstOrNull()?.let { Note(it.text.take(160)) }
                    }
                }
            }
            return@LazyColumn
        }
        if (searchQuery.isNotBlank() && searchAllDates) {
            item {
                SectionLabel("전체 기간 검색 · ${matchingRecords.size}개 기록")
                if (matchingRecords.isEmpty()) Note("일치하는 기록이 없습니다. 검색어를 바꾸세요.")
            }
            items(matchingRecords.sortedByDescending { it.createdAt }, key = { "search:${it.id}" }) { row ->
                Card(onClick = { selected = row.id }, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Note("${localDate(row.createdAt)} · ${row.source.title} · ${recordTime(row)}")
                        Text(RecordSearchPolicy.excerpt(row, peopleById, searchQuery), style = MaterialTheme.typography.bodyMedium)
                        if (row.transcriptIncomplete) Note("전사 누락 구간 있음")
                    }
                }
            }
            return@LazyColumn
        }
        item {
            SectionLabel("기록 일정")
            Text(if (monthly) "${date.year}년 ${date.monthValue}월" else "${date.monthValue}월 ${date.dayOfMonth}일",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!monthly, { monthly = false }, label = { Text("일별") })
                FilterChip(monthly, { monthly = true }, label = { Text("월별") })
                TextButton(onClick = { dateText = LocalDate.now().toString() }) { Text("오늘") }
            }
            OpticalQuickPair(first = { control ->
                ToolOutlinedButton(onClick = { dateText = (if (monthly) date.minusMonths(1) else date.minusDays(1)).toString() }, modifier = control) { Text(if (monthly) "이전 달" else "이전 날") }
            }, second = { control ->
                ToolOutlinedButton(onClick = { dateText = (if (monthly) date.plusMonths(1) else date.plusDays(1)).toString() }, modifier = control) { Text(if (monthly) "다음 달" else "다음 날") }
            })

        }
        val begin = (if (monthly) date.withDayOfMonth(1) else date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = (if (monthly) date.withDayOfMonth(1).plusMonths(1) else date.plusDays(1)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val records = matchingRecords.filter { row ->
            row.createdAt < end && calendarRecordEnd(row, now) > begin
        }.sortedBy { it.createdAt }
        val recordsByDay = records.flatMap { row -> calendarRecordDays(row, begin, end, now).map { it to row } }
            .groupBy({ it.first }, { it.second })
        val summariesByDay = matchingRecords.flatMap { it.summaries }.filter { it.kind == "day_end" && it.day.isNotBlank() }
            .sortedBy { it.stale }.distinctBy { it.day to it.text }
            .map { LocalDate.parse(it.day) to it }
            .filter { (day, _) -> day >= localDate(begin) && day < localDate(end) }
            .groupBy({ it.first }, { it.second })
        val days = (recordsByDay.keys + summariesByDay.keys).sorted()
        item {
            Text(if (query.isBlank()) "이 기간의 기록 ${records.size}개" else "검색 결과 ${records.size}개 기록",
                style = MaterialTheme.typography.titleSmall)
            if (days.isEmpty()) Note(if (query.isBlank()) "이 기간에 저장된 기록이 없습니다. 날짜를 바꾸거나 새 기록을 시작하세요."
                else "일치하는 기록이 없습니다. 검색어 또는 기간을 바꿔 보세요.")
            if (days.isEmpty() && query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("검색어 지우기") }
        }
        days.forEach { day ->
            val dayRecords = recordsByDay[day].orEmpty()
            val dayBegin = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            item(key = "day:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일 · ${dayRecords.size}개 기록") }
            summariesByDay[day].orEmpty().forEach { summary -> item {
                ContentCard {
                    Text(if (summary.stale) "이전 일일 요약 · 새로 고침 대기" else "일일 요약", style = MaterialTheme.typography.titleMedium)
                    if (summary.stale) Note("연결된 내용이 수정되었습니다. 아래는 이전 요약입니다.")
                    Text(summary.text)
                }
            } }
            dayRecords.groupBy { timePeriod(it.createdAt.coerceAtLeast(dayBegin)) }.forEach { (period, periodRecords) ->
                item(key = "period:$day:$period") { Note(period) }
                items(periodRecords, key = { "$day:${it.id}" }) { row ->
                    val daySegments = row.segments.filter { segment ->
                        row.timeOf(segment) < dayEnd && row.endTimeOf(segment) > dayBegin
                    }
                    Card(onClick = { selected = row.id }, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Note("${row.source.title} · ${recordTime(row)} · 당일 ${daySegments.size}개 원문")
                            if (row.transcriptOnly) Note(if (row.transcriptIncomplete) "전사만 보존 · 누락 구간 복구 불가" else "전사만 보존 · 원본 음성 없음")
                            else if (row.processingPending) Note("오디오 저장됨, 전사와 화자 분석 보충 대기")
                            if (searchQuery.isNotBlank()) Note(RecordSearchPolicy.excerpt(row, peopleById, searchQuery))
                            else if (row.source == ConversationSource.ALWAYS_ON) daySegments.take(3).forEach { segment ->
                                Note("${SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(row.timeOf(segment)))} · ${segment.text}")
                            } else if (row.summary.isNotBlank() && !row.summaryStale) Text(row.summary.take(160))
                            else if (row.summaryStale) daySegments.firstOrNull()?.let { Note(it.text.take(160)) }
                            if (row.summaryStale) Note("귀속이 수정되어 요약 재정리 대기")
                            if (row.derived.any { it.stale }) Note("연결된 Agent 결과를 새로 고쳐야 함")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechRow(segment: SpeechSegment, people: List<VoicePerson>, record: ConversationRecord? = null, action: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(onClick = action) else Modifier)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = people.firstOrNull { it.id == segment.personId }?.name ?: if (segment.speaker == "未知") "알 수 없는 화자" else "화자 ${KoreanLanguage.speakerLabel(segment.speaker)}"
            val start = record?.timeOf(segment) ?: segment.timelineAt.takeIf { it > 0 }
            val end = record?.endTimeOf(segment) ?: segment.timelineEndAt.takeIf { it > 0 }
            val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
            val time = start?.let { clock.format(Date(it)) + (end?.let { until -> "–${clock.format(Date(until))}" } ?: "") } ?: formatOffset(segment.beginMS)
            Note((if (segment.timeBasis == "legacy-estimate") "과거 시각 추정" else "휴대폰 수신 시각") + if (segment.processingMode == "backfill") " · 과거 오디오 보충" else "")
            Note("채널 ${segment.sourceChannel} · " + (when (segment.identityBasis) { "channel1_default_self" -> "채널 1 기본 본인"; "voiceprint" -> "음성 지문 일치"; "manual" -> "수동 수정"; "mixed" -> "혼합 식별"; else -> "신원 식별 대기" }))
            Note("$time · $name${if (segment.mixed) " · 겹침 또는 혼합" else if (!segment.stable) " · 잠정" else ""}")
            if (segment.candidates.isNotEmpty()) {
                Note("음성 지문 후보 · " + segment.candidates.joinToString(" · ") { match ->
                    "${people.firstOrNull { it.id == match.personId }?.name ?: "삭제된 인물"} ${"%.2f".format(match.score)}"
                })
            } else segment.matchScore?.let { Note("목소리 유사도 ${"%.2f".format(it)}") }
            if (segment.matchReason.isNotBlank()) Note(KoreanLanguage.matchReason(segment.matchReason))
            Text(segment.text)
        }
    }
}

@Composable
private fun ConversationDetail(model: AppModel, record: ConversationRecord, actions: UiActions, topicState: TopicUiState, back: () -> Unit) {
    val state = model.state.conversation
    var speakerEdit by remember { mutableStateOf<SpeechSegment?>(null) }
    var memoryEdit by remember { mutableStateOf<AttributedMemory?>(null) }
    var filter by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ordinaryDerived = record.derived.filterNot { it.id.startsWith(FollowUpPolicy.PREFIX) }
    // Four fixed items precede the optional sections.
    val timelineIndex = 4 + (if (ordinaryDerived.isNotEmpty()) 1 + ordinaryDerived.size else 0) +
        (if (record.speakerSamples.isNotEmpty()) 1 else 0) +
        (if (record.memories.isNotEmpty()) 1 + record.memories.size else 0)
    LazyColumn(state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back) { Text("기록으로 돌아가기") }
            OpticalPanel {
                Text(record.title, style = MaterialTheme.typography.headlineSmall)
                Note("${record.source.title} · ${recordTime(record)}")
                TopicMembership(topicState, record.id)
            }
            Note(if (record.source == ConversationSource.ALWAYS_ON) "종일 기록 타임라인과 요약. 원문을 탭하면 인물을 수정할 수 있습니다." else "${record.mode.title} · 원문을 탭하면 화자를, 기억을 탭하면 서술 대상을 수정할 수 있습니다.")
            OpticalQuickPair(first = { control ->
                ToolOutlinedButton(onClick = { scope.launch { list.scrollToItem(2) } }, modifier = control) { Text("요약 보기") }
            }, second = { control ->
                ToolOutlinedButton(onClick = { scope.launch { list.scrollToItem(timelineIndex) } }, modifier = control) { Text("원문 ${record.segments.size}개 보기") }
            })
        }
        item(key = "record-actions") {
            SectionLabel("기록 관리")
            if (record.transcriptOnly) Note(if (record.transcriptIncomplete) "완료된 전사만 보존했습니다. 원본 음성이 없어 누락 구간을 복구할 수 없습니다." else "원본 음성을 저장하지 않은 기록입니다. WAV 내보내기와 보충 전사를 사용할 수 없습니다.")
            else if (record.processingPending) Note("원본 오디오가 저장되었습니다. 전사와 화자 분석은 보충 대기 중이며 완료되면 함께 표시됩니다.")
            ToolButton(onClick = { model.service().conversations.summarize(record.id) }, enabled = !state.summarizing && record.segments.isNotEmpty()) {
                Text(if (state.summarizing) "정리 중…" else "요약 및 기억 정리")
            }
            if (!record.transcriptOnly && record.audioSamples > 0) {
                Note("${record.audioChannels}채널 원본 오디오 ${record.audioSamples / 16000}초 저장됨. 휴대폰에 암호화 저장되며 컴퓨터는 필요할 때 가져옵니다.")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolOutlinedButton(onClick = { actions.exportRecording(record.id) }, modifier = Modifier.fillMaxWidth()) { Text("원본 음성 내보내기 (WAV)") }
                    TextButton(onClick = { model.attempt { model.service().retranscribe(record.id) } }, enabled = !state.running && !state.stopping) { Text("원본 오디오에서 누락 구간 보충") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item(key = "record-summary") {
            if (record.summary.isBlank() && record.summaries.none { it.kind != "rolling" } && !record.summaryStale)
                Note("저장된 요약이 없습니다. 기록 관리에서 요약 및 기억 정리를 실행할 수 있습니다.")
            if (record.summary.isNotBlank()) {
                OpticalPanel {
                    SectionLabel(if (record.summaryStale) "이전 요약" else "AI 요약")
                    if (record.summaryStale) Note("내용이 업데이트되었습니다. 요약을 다시 정리하세요.")
                    Text(record.summary)
                }
            } else if (record.summaryStale) Note("내용이 업데이트되었습니다. 요약을 다시 정리하세요.")
            record.summaries.filter { it.kind != "rolling" }.forEach { summary ->
                Text(if (summary.kind == "day_end") "${summary.day.ifBlank { "날짜 미기록" }} 일일 요약" else "기록 종료 요약", style = MaterialTheme.typography.titleSmall)
                if (summary.stale) Note("출처가 수정되어 새로 고침 대기 · 아래는 이전 요약")
                Text(summary.text)
            }
        }
        item { FollowUpSection(model, record) }
        if (ordinaryDerived.isNotEmpty()) item { SectionLabel("연결된 Agent 분석 및 메모") }
        items(ordinaryDerived, key = { "derived:${it.id}" }) { derived ->
            OpticalPanel {
                Text(when (derived.kind) { "analysis" -> "분석"; "tags" -> "태그"; else -> "추가 메모" }, style = MaterialTheme.typography.titleSmall)
                if (derived.text.isNotBlank()) Text(derived.text)
                if (derived.tags.isNotEmpty()) Note(derived.tags.joinToString(" · "))
                if (derived.stale) Note("출처가 수정되어 원래 Agent가 새로 고쳐야 함")
            }
        }
        if (record.speakerSamples.isNotEmpty()) item {
            SectionLabel("수집된 목소리 · 탭하여 이름 지정")
            record.speakerSamples.forEach { (label, samples) ->
                val segment = record.segments.firstOrNull { it.speaker == label }
                    ?: SpeechSegment("voice:$label", 0, 0, "이번 세션에서 자동 추출: ${samples.size}개 음성 특징", speaker = label, personId = record.speakerPeople[label])
                TextButton(onClick = { speakerEdit = segment }) {
                    Text("화자 ${KoreanLanguage.speakerLabel(label)} · ${state.people.firstOrNull { it.id == segment.personId }?.name ?: "라벨 대기"} · ${samples.size}개 샘플")
                }
            }
        }
        if (record.memories.isNotEmpty()) item { SectionLabel("출처가 있는 기억") }
        items(record.memories, key = { it.id }) { memory ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { memoryEdit = memory }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${memory.subject} · ${KoreanLanguage.memoryKind(memory.kind)}", style = MaterialTheme.typography.titleSmall)
                    Text(memory.text)
                    memory.sourceIds.forEach { id -> record.segments.firstOrNull { it.id == id }?.let { source -> Note("원문: ${source.text}") } }
                }
            }
        }
        item {
            SectionLabel("원문 타임라인")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter == null, { filter = null }, label = { Text("전체") })
                record.segments.map { it.speaker }.distinct().forEach { name ->
                    FilterChip(filter == name, { filter = name }, label = { Text(KoreanLanguage.speakerLabel(name)) })
                }
            }
        }
        record.segments.filter { filter == null || it.speaker == filter }.groupBy { localDate(record.timeOf(it)) }.forEach { (day, segments) ->
            item(key = "timeline:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일") }
            items(segments, key = { it.id }) { segment ->
                SpeechRow(segment, state.people, record) { speakerEdit = segment }
            }
        }
    }
    speakerEdit?.let { segment -> SpeakerBindingDialog(model, record, segment, actions) { speakerEdit = null } }
    memoryEdit?.let { memory ->
        var subject by remember(memory.id) { mutableStateOf(memory.subject) }
        var text by remember(memory.id) { mutableStateOf(memory.text) }
        AlertDialog(onDismissRequest = { memoryEdit = null }, title = { Text("기억 귀속 수정") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("이 일은 누구에 대한 것인가") })
                OutlinedTextField(text, { text = it }, label = { Text("기억 내용") })
                Note("화자와 원문 출처는 유지됩니다. 저장 후 요약을 다시 정리해야 합니다.")
            }
        }, confirmButton = { TextButton(onClick = {
            model.service().conversations.correctMemory(record.id, memory.id, subject.trim(), text.trim()); memoryEdit = null
        }, enabled = subject.isNotBlank() && text.isNotBlank()) { Text("수정 저장") } }, dismissButton = { TextButton(onClick = { memoryEdit = null }) { Text("취소") } })
    }
}

@Composable
private fun SpeakerBindingDialog(model: AppModel, record: ConversationRecord, segment: SpeechSegment, actions: UiActions, dismiss: () -> Unit) {
    val people = model.state.conversation.people
    var history by remember { mutableStateOf(false) }
    if (history) {
        HistoricalSpeakerDialog(model, segment.personId, record.id, segment.id, dismiss = dismiss)
        return
    }
    var personId by remember { mutableStateOf(segment.personId ?: record.speakerPeople[segment.speaker]) }
    var name by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(segment.speaker != "未知") }
    var rememberVoice by remember { mutableStateOf(record.speakerSamples[segment.speaker]?.isNotEmpty() == true) }
    val picked = model.selectedContact
    LaunchedEffect(picked) { if (picked != null) { name = picked.name; personId = null } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("화자 ${KoreanLanguage.speakerLabel(segment.speaker)}") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(segment.text)
            TextButton(onClick = { history = true }) { Text("과거 발화를 선택해 일괄 수정") }
            Row { Checkbox(all, { all = it }, enabled = segment.speaker != "未知"); Text("이번 기록의 같은 화자에게 적용") }
            people.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { personId = person.id }) {
                    RadioButton(personId == person.id, { personId = person.id }); Text(person.name)
                }
            }
            OutlinedTextField(name, { name = it; personId = null }, label = { Text("또는 새 인물 이름") })
            TextButton(onClick = { model.selectedContact = null; actions.contact() }) { Text("연락처에서 선택") }
            Row { Checkbox(rememberVoice, { rememberVoice = it }, enabled = all && record.speakerSamples[segment.speaker]?.isNotEmpty() == true)
                Text("이 목소리를 기억해 다음에 식별") }
            Note("자동 수집됨: ${record.speakerSamples[segment.speaker].orEmpty().size}개 음성 지문. 체크하면 이름 붙인 목소리가 다음 세션 매칭에 사용되고, 끄면 이번 라벨만 변경됩니다. 유사도는 목소리 비교 점수입니다.")
        }
    }, confirmButton = { TextButton(onClick = {
        val person = people.firstOrNull { it.id == personId } ?: VoicePerson(name = name.trim(), contactUri = picked?.uri)
        if (all) model.service().conversations.bind(record.id, segment.speaker, person, rememberVoice)
        else {
            model.service().conversations.correctSpeaker(record.id, setOf(segment.id), person.id, person)
        }
        model.selectedContact = null; dismiss()
    }, enabled = personId != null || name.isNotBlank()) { Text("저장") } }, dismissButton = {
        TextButton(onClick = { model.selectedContact = null; dismiss() }) { Text("취소") }
    })
}

private fun formatOffset(milliseconds: Long): String = "%02d:%02d".format(milliseconds / 60000, milliseconds / 1000 % 60)

private fun localDate(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
private fun calendarRecordEnd(record: ConversationRecord, now: Long): Long = record.endedAt
    ?: if (record.audioPending) now else record.segments.maxOfOrNull { record.endTimeOf(it) } ?: record.createdAt
private fun calendarRecordDays(record: ConversationRecord, begin: Long, end: Long, now: Long): List<LocalDate> {
    val start = record.createdAt.coerceAtLeast(begin)
    val until = calendarRecordEnd(record, now).coerceAtMost(end)
    if (until <= start) return emptyList()
    val lastDay = localDate(until - 1)
    return generateSequence(localDate(start)) { day -> day.plusDays(1).takeIf { it <= lastDay } }.toList()
}
private fun timePeriod(time: Long): String = when (Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).hour) {
    in 0..5 -> "새벽 00:00–06:00"
    in 6..11 -> "오전 06:00–12:00"
    in 12..17 -> "오후 12:00–18:00"
    else -> "저녁 18:00–24:00"
}
private fun recordTime(record: ConversationRecord): String {
    val format = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    return format.format(Date(record.createdAt)) + " → " + (record.endedAt?.let { format.format(Date(it)) } ?: "종료 시각 미기록")
}

/** Select exact historical turns before changing identity; original ASR and audio remain intact. */
@Composable
internal fun HistoricalSpeakerDialog(
    model: AppModel, personId: String?, initialRecordId: String? = null, initialSegmentId: String? = null,
    split: Boolean = false, dismiss: () -> Unit,
) {
    val state = model.state.conversation
    val rows = state.records.map { record -> record to record.segments.filter { segment ->
        if (personId != null) segment.personId == personId else initialRecordId == null || record.id == initialRecordId
    } }.filter { it.second.isNotEmpty() }.sortedByDescending { it.first.createdAt }
    val selection = remember(personId, initialRecordId, initialSegmentId, split) {
        mutableStateMapOf<String, Set<String>>().apply {
            if (initialRecordId != null && initialSegmentId != null) put(initialRecordId, setOf(initialSegmentId))
        }
    }
    var expanded by remember { mutableStateOf(setOfNotNull(initialRecordId)) }
    var targetId by remember { mutableStateOf(personId) }
    var name by remember { mutableStateOf("") }
    val selected = selection.filterValues { it.isNotEmpty() }
    val count = selected.values.sumOf { it.size }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (split) "인물의 과거 발화 분리" else "과거 인물 귀속 일괄 수정") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("먼저 기록을 선택하거나 펼쳐서 발화를 고르세요. 이번에 수정할 항목: 기록 ${selected.size}개, ${count}개 원문. 관련 요약과 Agent 결과는 새로 고침 필요로 표시되며 원문과 오디오는 유지됩니다.")
            if (split) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("분리할 새 인물 이름") })
            else {
                Text("다음으로 수정", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().clickable { targetId = null }) {
                    RadioButton(targetId == null, { targetId = null }); Text("알 수 없는 화자")
                }
                state.people.filter { it.mergedInto == null }.forEach { person ->
                    Row(Modifier.fillMaxWidth().clickable { targetId = person.id }) {
                        RadioButton(targetId == person.id, { targetId = person.id }); Text(person.name)
                    }
                }
            }
            HorizontalDivider()
            if (rows.isEmpty()) Note("수정할 수 있는 연결 발화가 없습니다.")
            rows.forEach { (record, turns) ->
                val ids = turns.map { it.id }.toSet()
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(ids.isNotEmpty() && selection[record.id].orEmpty().containsAll(ids), { checked ->
                        selection[record.id] = if (checked) ids else emptySet()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(record.title, style = MaterialTheme.typography.titleSmall)
                        Note("${recordTime(record)} · ${selection[record.id].orEmpty().size}/${turns.size}개")
                        TextButton(onClick = { expanded = if (record.id in expanded) expanded - record.id else expanded + record.id }) {
                            Text(if (record.id in expanded) "발화 접기" else "발화 고르기")
                        }
                    }
                }
                if (record.id in expanded) turns.forEach { turn ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(turn.id in selection[record.id].orEmpty(), { checked ->
                            val old = selection[record.id].orEmpty()
                            selection[record.id] = if (checked) old + turn.id else old - turn.id
                        })
                        Column(Modifier.weight(1f).padding(top = 10.dp)) { Note(formatOffset(turn.beginMS)); Text(turn.text) }
                    }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = {
        model.attempt {
            if (split) model.service().conversations.splitPerson(requireNotNull(personId), name.trim(), selected)
            else model.service().conversations.correctSpeakers(selected, targetId)
        }
        dismiss()
    }, enabled = count > 0 && (!split || name.isNotBlank())) { Text("${count}개 ${if (split) "분리" else "수정"} 확인") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
