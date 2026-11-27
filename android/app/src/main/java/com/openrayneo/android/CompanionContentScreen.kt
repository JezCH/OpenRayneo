// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Separate ephemeral drafts; neither is persisted into saved instance state. */
internal data class ContentEditDraft(
    val title: String = "", val start: String = "", val end: String = "",
    val id: Long? = null, val device: String? = null,
    val originalTitle: String = "", val originalStart: String = "", val originalEnd: String = "",
    val requestId: String? = null, val submittedDevice: String? = null,
) {
    val dirty: Boolean get() = title != originalTitle || start != originalStart || end != originalEnd
    val unresolved: Boolean get() = requestId != null
}
internal class ContentEditorDrafts {
    var todo by mutableStateOf(ContentEditDraft())
    var schedule by mutableStateOf(ContentEditDraft())
}
private data class ContentDeleteReview(val page: Int, val id: Long, val title: String, val row: String, val device: String)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CompanionContentScreen(model: AppModel, drafts: ContentEditorDrafts) {
    val state = model.state.content
    val connected = model.state.available && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting
    val editable = connected && !state.busy
    var page by rememberSaveable { mutableIntStateOf(0) }
    val draft = if (page == 2) drafts.schedule else drafts.todo
    val title = draft.title
    val start = draft.start
    val end = draft.end
    val editId = draft.id
    val device = if (connected) runCatching { model.service().authenticatedNavigationDevice }.getOrNull() else null
    val editReady = editable && device != null && (editId == null || draft.device == device) &&
        (draft.submittedDevice == null || draft.submittedDevice == device)
    val inputsEnabled = !state.busy && !draft.unresolved
    fun setDraft(value: ContentEditDraft, targetPage: Int = page) {
        if (targetPage == 2) drafts.schedule = value else drafts.todo = value
    }
    fun currentDraft(targetPage: Int) = if (targetPage == 2) drafts.schedule else drafts.todo
    var replaceDraft by remember { mutableStateOf<ContentEditDraft?>(null) }
    var deleteReview by remember { mutableStateOf<ContentDeleteReview?>(null) }
    LaunchedEffect(device) { deleteReview = null; replaceDraft = null }
    var cityQuery by rememberSaveable { mutableStateOf("") }
    val formatter = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT) }
    val scheduleStart = remember(start) { runCatching { LocalDateTime.parse(start, formatter) }.getOrNull() }
    val scheduleEnd = remember(end) { runCatching { LocalDateTime.parse(end, formatter) }.getOrNull() }
    val validSchedule = scheduleStart != null && scheduleEnd != null && scheduleEnd.isAfter(scheduleStart)
    val editor = remember { BringIntoViewRequester() }
    var editRequest by remember { mutableIntStateOf(0) }
    fun chooseDraft(next: ContentEditDraft) {
        if (draft.dirty || draft.unresolved) replaceDraft = next
        else { setDraft(next); editRequest++ }
    }
    fun reviewDelete(row: JSONObject) {
        if (editable && device != null) deleteReview = ContentDeleteReview(page, row.getLong("eventID"), row.optString("title"), row.toString(), device)
    }
    replaceDraft?.let { next ->
        AlertDialog(onDismissRequest = { replaceDraft = null },
            title = { Text("저장하지 않은 편집 내용") },
            text = { Text(if (draft.unresolved) "이전 요청의 결과가 아직 확인되지 않았습니다. 먼저 기기 목록을 읽어 중복 여부를 확인하세요. 현재 입력을 닫아도 이미 전송한 요청은 취소되지 않습니다."
                else "현재 입력을 버리고 다른 항목을 편집할까요? 저장된 안경 콘텐츠는 삭제하지 않습니다.") },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { setDraft(next); replaceDraft = null; editRequest++ }) { Text("편집 종료") } },
            dismissButton = { TextButton(onClick = { replaceDraft = null }) { Text("계속 편집") } })
    }
    deleteReview?.let { review ->
        val current = (if (review.page == 1) state.todos else state.schedules).firstOrNull { it.optLong("eventID") == review.id }
        val valid = editable && page == review.page && device == review.device && current?.toString() == review.row
        AlertDialog(onDismissRequest = { deleteReview = null },
            title = { Text(if (review.page == 1) "할 일을 삭제할까요?" else "일정을 삭제할까요?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.title)
                Note("현재 연결된 안경에 삭제 요청을 보냅니다. 재조회 결과를 확인하세요.")
                if (!valid) Note("기기 또는 항목 상태가 변경되었습니다. 취소 후 목록에서 다시 선택하세요.")
            } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                deleteReview = null
                model.attempt {
                    check(model.service().authenticatedNavigationDevice == review.device) { "안경 연결이 변경되었습니다" }
                    val content = model.service().content
                    content.run {
                        val actual = if (review.page == 1) content.events(1) else content.events(0)
                        check(actual.any { it.optLong("eventID") == review.id && it.toString() == review.row } ||
                            review.page == 1 && content.state.todos.any { it.optLong("eventID") == review.id && it.toString() == review.row }) {
                            "기기 목록이 변경되었습니다. 다시 확인하세요"
                        }
                        check(model.service().authenticatedNavigationDevice == review.device) { "안경 연결이 변경되었습니다" }
                        val args = JSONObject().put("event_id", review.id)
                        if (review.page == 1) content.todo("delete", args) else content.schedule("delete", args)
                    }
                }
            }) { Text("삭제 요청") } },
            dismissButton = { TextButton(onClick = { deleteReview = null }) { Text("취소") } })
    }
    // Retrying a submission uses the same mutation ID and same payload.
    // Only a verified readback clears the unresolved state automatically.
    fun submitDraft() {
        val targetPage = page
        val targetDevice = device ?: return
        if (!editReady) return
        val submitted = draft.copy(title = title.trim(),
            requestId = draft.requestId ?: java.util.UUID.randomUUID().toString(),
            submittedDevice = targetDevice)
        val args = JSONObject().put("title", submitted.title).put("request_id", submitted.requestId)
        submitted.id?.let { args.put("event_id", it) }
        if (targetPage == 2) {
            args.put("start_time", LocalDateTime.parse(submitted.start, formatter).atZone(ZoneId.systemDefault()).toEpochSecond())
                .put("end_time", LocalDateTime.parse(submitted.end, formatter).atZone(ZoneId.systemDefault()).toEpochSecond())
        }
        val service = model.service()
        check(service.authenticatedNavigationDevice == targetDevice) { "안경 연결이 변경되었습니다" }
        val content = service.content
        setDraft(submitted, targetPage)
        content.runWithResult({
            val action = if (submitted.id == null) "add" else "update"
            if (targetPage == 1) content.todo(action, args) else content.schedule(action, args)
        }) { result ->
            if (currentDraft(targetPage) != submitted) return@runWithResult
            result.onSuccess { response ->
                if (response.optBoolean("readBack") && response.has("eventID")) {
                    setDraft(submitted.copy(id = response.getLong("eventID"), device = targetDevice,
                        originalTitle = submitted.title, originalStart = submitted.start, originalEnd = submitted.end,
                        requestId = null, submittedDevice = null), targetPage)
                }
            }
        }
    }
    LaunchedEffect(page, editId, editRequest) {
        if (editRequest > 0 && page in 1..2) {
            withFrameNanos {}
            editor.bringIntoView()
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OpticalPanel {
            Text("안경 콘텐츠 상태", style = MaterialTheme.typography.titleLarge)
            Text(deviceConnectionStatus(model.state).label, style = MaterialTheme.typography.labelLarge)
            Note(if (connected) state.notice else "안경 연결 후 현재 콘텐츠를 확인하세요. 아래 데이터는 현재 기기 값으로 확인되지 않았습니다.")
            ToolButton(onClick = { model.attempt { model.service().content.refresh() } }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "기기 응답 대기 중…" else "안경 콘텐츠 다시 읽기") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("레이아웃", "할 일", "일정", "날씨").forEachIndexed { i, name ->
                val pending = (i == 1 && drafts.todo.unresolved) || (i == 2 && drafts.schedule.unresolved)
                val dirty = (i == 1 && drafts.todo.dirty) || (i == 2 && drafts.schedule.dirty)
                FilterChip(page == i, { page = i; replaceDraft = null; deleteReview = null }, label = { Text(name + if (pending) " · 확인 대기" else if (dirty) " · 편집 중" else "") })
            }
        }
        when (page) {
            0 -> DashboardLayoutEditor(model)
            1 -> {
                SectionLabel("할 일 목록 · ${state.todos.size}개")
                ToolOutlinedButton(onClick = { chooseDraft(ContentEditDraft()) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("새 할 일 작성") }
                if (state.todos.isEmpty()) Note("표시할 할 일이 없습니다. 연결 후 콘텐츠를 읽거나 새 할 일을 추가하세요.")
                state.todos.forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(row.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Note(if (row.optString("syncState") == "outcome_unknown") "변경 결과 확인 대기" else if (row.optInt("status") == 1) "완료됨" else "미완료")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val name = row.getString("title")
                                chooseDraft(ContentEditDraft(title = name, id = row.getLong("eventID"), device = device, originalTitle = name))
                            }, enabled = editable && device != null) { Text("편집") }
                            TextButton(onClick = { model.attempt { model.service().content.run {
                                model.service().content.todo("update", JSONObject().put("event_id", row.getLong("eventID")).put("completed", row.optInt("status") != 1))
                            } } }, enabled = editable) { Text(if (row.optInt("status") == 1) "완료 취소" else "완료") }
                            TextButton(onClick = { reviewDelete(row) }, enabled = editable && device != null) { Text("삭제") }
                        }
                    } }
                }
                OpticalPanel {
                        SectionLabel(if (editId == null) "새 할 일" else "할 일 편집")
                        if (editId != null && draft.device != device) Note("이 편집은 다른 연결의 항목입니다. 원래 안경을 연결하거나 새 항목을 작성하세요.")
                        if (draft.unresolved) Note("이전 요청 결과 미확인. 목록을 다시 읽어 확인하거나 같은 요청 ID로 확인을 재시도하세요. 새 항목을 만들기 전 중복 여부를 확인하세요.")
                        else if (draft.dirty) Note("편집 내용은 앱의 현재 화면 세션에만 유지됩니다. 저장 후 기기 재조회 결과를 확인하세요.")
                        OutlinedTextField(title, { setDraft(draft.copy(title = it)) }, Modifier.fillMaxWidth().bringIntoViewRequester(editor), label = { Text("할 일 제목") }, enabled = inputsEnabled)
                ToolButton(onClick = { model.attempt { submitDraft() } }, enabled = editReady && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (draft.unresolved) "같은 요청 확인 재시도" else if (editId == null) "안경에 추가" else "할 일 저장")
                }
                if (editId != null || draft.dirty || draft.unresolved) TextButton(onClick = { chooseDraft(ContentEditDraft()) }, enabled = !state.busy) { Text("편집 종료") }
                }
            }
            2 -> {
                SectionLabel("일정 목록 · ${state.schedules.size}개")
                ToolOutlinedButton(onClick = { chooseDraft(ContentEditDraft()) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("새 일정 작성") }
                if (state.schedules.isEmpty()) Note("표시할 일정이 없습니다. 연결 후 콘텐츠를 읽거나 새 일정을 추가하세요.")
                state.schedules.forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(row.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Note(java.time.Instant.ofEpochSecond(row.getLong("startTime")).atZone(ZoneId.systemDefault()).format(formatter) + " — " +
                            java.time.Instant.ofEpochSecond(row.getLong("endTime")).atZone(ZoneId.systemDefault()).format(formatter))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val name = row.getString("title")
                                val from = java.time.Instant.ofEpochSecond(row.getLong("startTime")).atZone(ZoneId.systemDefault()).format(formatter)
                                val to = java.time.Instant.ofEpochSecond(row.getLong("endTime")).atZone(ZoneId.systemDefault()).format(formatter)
                                chooseDraft(ContentEditDraft(name, from, to, row.getLong("eventID"), device, name, from, to))
                            }, enabled = editable && device != null) { Text("편집") }
                            TextButton(onClick = { reviewDelete(row) }, enabled = editable && device != null) { Text("삭제") }
                        }
                    } }
                }
                OpticalPanel {
                        SectionLabel(if (editId == null) "새 일정" else "일정 편집")
                        if (editId != null && draft.device != device) Note("이 편집은 다른 연결의 항목입니다. 원래 안경을 연결하거나 새 항목을 작성하세요.")
                        if (draft.unresolved) Note("이전 요청 결과 미확인. 목록을 다시 읽어 확인하거나 같은 요청 ID로 확인을 재시도하세요. 새 항목을 만들기 전 중복 여부를 확인하세요.")
                        else if (draft.dirty) Note("편집 내용은 앱의 현재 화면 세션에만 유지됩니다. 저장 후 기기 재조회 결과를 확인하세요.")
                        OutlinedTextField(title, { setDraft(draft.copy(title = it)) }, Modifier.fillMaxWidth().bringIntoViewRequester(editor), label = { Text("일정 제목") }, enabled = inputsEnabled)
                OutlinedTextField(start, { setDraft(draft.copy(start = it)) }, Modifier.fillMaxWidth(), label = { Text("시작: yyyy-MM-dd HH:mm") }, singleLine = true, enabled = inputsEnabled, isError = start.isNotBlank() && scheduleStart == null)
                OutlinedTextField(end, { setDraft(draft.copy(end = it)) }, Modifier.fillMaxWidth(), label = { Text("종료: yyyy-MM-dd HH:mm") }, singleLine = true, enabled = inputsEnabled, isError = end.isNotBlank() && (scheduleEnd == null || scheduleStart != null && !validSchedule))
                if (start.isNotBlank() || end.isNotBlank()) {
                    if (scheduleStart == null || scheduleEnd == null) Note("날짜와 시간을 yyyy-MM-dd HH:mm 형식으로 정확히 입력하세요.")
                    else if (!validSchedule) Note("종료 시간은 시작 시간보다 뒤여야 합니다.")
                }
                ToolButton(onClick = { model.attempt { submitDraft() } }, enabled = editReady && title.isNotBlank() && validSchedule, modifier = Modifier.fillMaxWidth()) {
                    Text(if (draft.unresolved) "같은 요청 확인 재시도" else if (editId == null) "안경에 추가" else "일정 저장")
                }
                if (editId != null || draft.dirty || draft.unresolved) TextButton(onClick = { chooseDraft(ContentEditDraft()) }, enabled = !state.busy) { Text("편집 종료") }
                }
            }
            3 -> {
                ToolButton(onClick = { model.attempt { val content = model.service().content
                    content.run { content.weather(content.currentCity()); content.pushWeather() }
                } }, enabled = editable) { Text("현재 위치 날씨를 안경에 표시") }
                Note("휴대폰 위치로 날씨를 가져와 안경에 보냅니다. 자동 업데이트를 켜면 매번 현재 위치를 다시 찾습니다. 다른 도시는 아래에서 검색하세요.")
                OutlinedTextField(cityQuery, { cityQuery = it }, Modifier.fillMaxWidth(), label = { Text("도시 검색") })
                ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.searchCities(cityQuery) } } }, enabled = !state.busy && cityQuery.isNotBlank()) { Text("도시 찾기") }
                state.weatherCities.forEach { city -> TextButton(onClick = { model.attempt { model.service().content.run { model.service().content.weather(city) } } }, enabled = editable) {
                    Text(listOf(city.optString("name"), city.optString("admin1"), city.optString("country")).filter { it.isNotBlank() }.joinToString(" · "))
                } }
                state.weather?.let { card ->
                    Text("${card.getString("location")} · ${card.getInt("temp")}° · ${card.getString("des")}", style = MaterialTheme.typography.titleMedium)
                    ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.pushWeather() } } }, enabled = editable) { Text("홈 온도와 날씨 카드 동기화") }
                    Row {
                        Checkbox(state.automaticWeather, { model.attempt { model.service().content.setAutomaticWeather(it) } }, enabled = editable)
                        Text("연결 시 및 30분마다 날씨 업데이트")
                    }
                    ToolOutlinedButton(onClick = { model.attempt { model.service().content.automaticWeather(force = true) } }, enabled = editable && state.automaticWeather) { Text("지금 자동 업데이트") }
                    Note("홈 온도는 여기서 고른 도시나 현재 위치를 사용합니다. 날씨는 Open-Meteo에서 제공하며 연결이 끊기면 업데이트가 중지됩니다.")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardLayoutEditor(model: AppModel) {
    val state = model.state.content
    val config = state.dashboard
    if (config == null) { Note("안경 콘텐츠를 읽은 뒤 홈 스타일과 위젯 순서를 설정할 수 있습니다."); return }
    var mode by remember(config.toString()) { mutableIntStateOf(config.optInt("simple_mode")) }
    var widgets by remember(config.toString()) { mutableStateOf(config.optJSONArray("widgets").ints()) }
    val names = mapOf(1 to "날씨", 2 to "할 일", 3 to "일정", 4 to "주식", 5 to "메일", 6 to "세계 시계")
    val enabled = !state.busy && model.state.available && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting
    SectionLabel("홈 스타일")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("표준", "간결", "대각 분할").forEachIndexed { index, title -> FilterChip(mode == index, { mode = index }, label = { Text(title) }) }
    }
    SectionLabel("위젯 순서")
    widgets.forEachIndexed { index, id ->
        OpticalPanel {
            Text("${index + 1}. " + (names[id] ?: "기기 위젯$id"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Text("위로") }
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < widgets.lastIndex) { Text("아래로") }
            TextButton(onClick = { widgets = widgets.filter { it != id } }, enabled = widgets.size > 1) { Text("제거") }
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1,2,3,6).filter { it !in widgets }.forEach { id ->
            TextButton(onClick = { widgets = widgets + id }, enabled = widgets.size < 5 && (id != 1 || state.weather != null)) { Text("추가: ${names[id]}") }
        }
    }
    Note("날씨는 먼저 날씨 탭에서 도시를 설정하세요. 세계 시계는 휴대폰의 현재 시간대를 사용합니다. 기존 주식·메일 위젯은 유지되며 데이터 소스는 원래 설정을 따릅니다.")
    ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.layout(mode, widgets) } } }, enabled = enabled) { Text("홈 레이아웃 적용") }
}
