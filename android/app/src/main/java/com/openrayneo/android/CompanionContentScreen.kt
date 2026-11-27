// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
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

@Composable
internal fun CompanionContentScreen(model: AppModel) {
    val state = model.state.content
    val connected = model.state.available && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting
    val editable = connected && !state.busy
    var page by rememberSaveable { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var cityQuery by rememberSaveable { mutableStateOf("") }
    val formatter = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT) }
    val scheduleStart = remember(start) { runCatching { LocalDateTime.parse(start, formatter) }.getOrNull() }
    val scheduleEnd = remember(end) { runCatching { LocalDateTime.parse(end, formatter) }.getOrNull() }
    val validSchedule = scheduleStart != null && scheduleEnd != null && scheduleEnd.isAfter(scheduleStart)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ContentCard {
            Text("안경 콘텐츠 상태", style = MaterialTheme.typography.titleLarge)
            Text(deviceConnectionStatus(model.state).label, style = MaterialTheme.typography.labelLarge)
            Note(if (connected) state.notice else "안경 연결 후 현재 콘텐츠를 확인하세요. 아래 데이터는 현재 기기 값으로 확인되지 않았습니다.")
            ToolButton(onClick = { model.attempt { model.service().content.refresh() } }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "기기 응답 대기 중…" else "안경 콘텐츠 다시 읽기") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("레이아웃", "할 일", "일정", "날씨").forEachIndexed { i, name -> FilterChip(page == i, { page = i; editId = null; title = "" }, label = { Text(name) }) }
        }
        when (page) {
            0 -> DashboardLayoutEditor(model)
            1 -> {
                SectionLabel("할 일 목록 · ${state.todos.size}개")
                if (state.todos.isEmpty()) Note("표시할 할 일이 없습니다. 연결 후 콘텐츠를 읽거나 새 할 일을 추가하세요.")
                state.todos.forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(row.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Note(if (row.optString("syncState") == "outcome_unknown") "변경 결과 확인 대기" else if (row.optInt("status") == 1) "완료됨" else "미완료")
                        Row {
                            TextButton(onClick = { title = row.getString("title"); editId = row.getLong("eventID") }) { Text("편집") }
                            TextButton(onClick = { model.attempt { model.service().content.run {
                                model.service().content.todo("update", JSONObject().put("event_id", row.getLong("eventID")).put("completed", row.optInt("status") != 1))
                            } } }, enabled = editable) { Text(if (row.optInt("status") == 1) "완료 취소" else "완료") }
                            TextButton(onClick = { model.attempt { model.service().content.run {
                                model.service().content.todo("delete", JSONObject().put("event_id", row.getLong("eventID")))
                            } } }, enabled = editable) { Text("삭제") }
                        }
                    } }
                }
                SectionLabel(if (editId == null) "새 할 일" else "할 일 편집")
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("할 일 제목") })
                ToolButton(onClick = { model.attempt { model.service().content.run {
                    model.service().content.todo(if (editId == null) "add" else "update", JSONObject().put("title", title.trim()).apply { editId?.let { put("event_id", it) } })
                } } }, enabled = editable && title.isNotBlank()) { Text(if (editId == null) "안경에 추가" else "할 일 저장") }
                if (editId != null) TextButton(onClick = { editId = null; title = "" }) { Text("편집 종료") }
            }
            2 -> {
                SectionLabel("일정 목록 · ${state.schedules.size}개")
                if (state.schedules.isEmpty()) Note("표시할 일정이 없습니다. 연결 후 콘텐츠를 읽거나 새 일정을 추가하세요.")
                state.schedules.forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(row.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Note(java.time.Instant.ofEpochSecond(row.getLong("startTime")).atZone(ZoneId.systemDefault()).format(formatter) + " — " +
                            java.time.Instant.ofEpochSecond(row.getLong("endTime")).atZone(ZoneId.systemDefault()).format(formatter))
                        Row {
                            TextButton(onClick = { title = row.getString("title"); editId = row.getLong("eventID")
                                start = java.time.Instant.ofEpochSecond(row.getLong("startTime")).atZone(ZoneId.systemDefault()).format(formatter)
                                end = java.time.Instant.ofEpochSecond(row.getLong("endTime")).atZone(ZoneId.systemDefault()).format(formatter)
                            }) { Text("편집") }
                            TextButton(onClick = { model.attempt { model.service().content.run {
                                model.service().content.schedule("delete", JSONObject().put("event_id", row.getLong("eventID")))
                            } } }, enabled = editable) { Text("삭제") }
                        }
                    } }
                }
                SectionLabel(if (editId == null) "새 일정" else "일정 편집")
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("일정 제목") })
                OutlinedTextField(start, { start = it }, Modifier.fillMaxWidth(), label = { Text("시작: yyyy-MM-dd HH:mm") }, singleLine = true, isError = start.isNotBlank() && scheduleStart == null)
                OutlinedTextField(end, { end = it }, Modifier.fillMaxWidth(), label = { Text("종료: yyyy-MM-dd HH:mm") }, singleLine = true, isError = end.isNotBlank() && (scheduleEnd == null || scheduleStart != null && !validSchedule))
                if (start.isNotBlank() || end.isNotBlank()) {
                    if (scheduleStart == null || scheduleEnd == null) Note("날짜와 시간을 yyyy-MM-dd HH:mm 형식으로 정확히 입력하세요.")
                    else if (!validSchedule) Note("종료 시간은 시작 시간보다 뒤여야 합니다.")
                }
                ToolButton(onClick = { model.attempt {
                    val args = JSONObject().put("title", title.trim()).put("start_time", LocalDateTime.parse(start, formatter).atZone(ZoneId.systemDefault()).toEpochSecond())
                        .put("end_time", LocalDateTime.parse(end, formatter).atZone(ZoneId.systemDefault()).toEpochSecond())
                    editId?.let { args.put("event_id", it) }
                    model.service().content.run { model.service().content.schedule(if (editId == null) "add" else "update", args) }
                } }, enabled = editable && title.isNotBlank() && validSchedule) { Text(if (editId == null) "안경에 추가" else "일정 저장") }
                if (editId != null) TextButton(onClick = { editId = null; title = "" }) { Text("편집 종료") }
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

@Composable
private fun DashboardLayoutEditor(model: AppModel) {
    val state = model.state.content
    val config = state.dashboard
    if (config == null) { Note("안경 콘텐츠를 읽은 뒤 홈 스타일과 위젯 순서를 설정할 수 있습니다."); return }
    var mode by remember(config.toString()) { mutableIntStateOf(config.optInt("simple_mode")) }
    var widgets by remember(config.toString()) { mutableStateOf(config.optJSONArray("widgets").ints()) }
    val names = mapOf(1 to "날씨", 2 to "할 일", 3 to "일정", 4 to "주식", 5 to "메일", 6 to "세계 시계")
    val enabled = !state.busy && model.state.available && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("표준", "간결", "대각 분할").forEachIndexed { index, title -> FilterChip(mode == index, { mode = index }, label = { Text(title) }) }
    }
    widgets.forEachIndexed { index, id ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(names[id] ?: "기기 위젯$id", Modifier.weight(1f))
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Text("위로") }
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < widgets.lastIndex) { Text("아래로") }
            TextButton(onClick = { widgets = widgets.filter { it != id } }, enabled = widgets.size > 1) { Text("제거") }
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
