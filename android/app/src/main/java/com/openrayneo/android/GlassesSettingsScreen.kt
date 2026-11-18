// Copyright 2026 Tobi1chi
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

@Composable
internal fun GlassesSettingsScreen(model: AppModel) {
    val snapshot = model.state.deviceSettings
    val connected = model.state.glasses.link.phase == LinkPhase.FULL
    val editable = connected && snapshot.loaded && !snapshot.busy
    val status = snapshot.status
    val settings = snapshot.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("안경 설정", style = MaterialTheme.typography.headlineMedium)
        Note("블루투스로 기기 설정을 직접 읽고 수정합니다. 사용 중에는 OpenRayneo가 안경 연결을 유지합니다.")
        ToolButton(onClick = { model.attempt { model.service().deviceSettings.refresh() } },
            enabled = connected && !snapshot.busy, modifier = Modifier.fillMaxWidth()) { Text("안경 설정 읽기") }
        if (snapshot.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Note(if (connected) snapshot.notice else "먼저 안경 탭에서 연결을 완료하세요")
        if (snapshot.loaded) {
            SettingsSection("기기 정보") {
                Text(status.optString("deviceName").ifBlank { model.deviceName }, style = MaterialTheme.typography.titleMedium)
                settings.optString("firmwareVersion").takeIf { it.isNotBlank() }?.let { Note("펌웨어 $it") }
                status.deviceInt("battery")?.let { Note("배터리 $it%") }
                status.deviceFlag("isCharging")?.let { Note(if (it) "충전 중" else "충전 안 함") }
            }
            status.deviceInt("brightness")?.let { current ->
                var value by remember(current) { mutableIntStateOf(current) }
                SettingsSection("표시 밝기") {
                    Text("밝기 $value · 현재 기기 값 $current")
                    Slider(value.toFloat(), { value = it.toInt() }, valueRange = 1f..17f, steps = 15, enabled = editable)
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.brightness(value) } }, enabled = editable) { Text("밝기 적용") }
                    status.deviceFlag("automaticBrightness")?.let { Note("자동 밝기: ${if (it) "켜짐" else "꺼짐"}") }
                }
            }
            val display = settings.optJSONObject("displayConfig")
            val currentHeight = display?.deviceInt("height")
            val currentDistance = display?.deviceInt("distance")
            if (currentHeight != null && currentDistance != null) {
                var height by remember(currentHeight) { mutableIntStateOf(currentHeight) }
                var distance by remember(currentDistance) { mutableIntStateOf(currentDistance) }
                SettingsSection("화면 위치") {
                    Text("높이 $height")
                    Slider(height.toFloat(), { height = it.toInt() }, valueRange = 1f..6f, steps = 4, enabled = editable)
                    Text("거리 $distance")
                    Slider(distance.toFloat(), { distance = it.toInt() }, valueRange = 1f..3f, steps = 1, enabled = editable)
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.display(height, distance) } }, enabled = editable) { Text("화면 위치 적용") }
                }
            }
            settings.deviceInt("autoLockTime")?.let { current ->
                var value by remember(current) { mutableIntStateOf(current) }
                SettingsSection("자동 절전") {
                    SettingsChoice("절전 시간", value, listOf(5, 10, 15, 25, 40, 60, 120).map { it to "${it}초" }, editable) { value = it }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.autoLock(value) } },
                        enabled = editable && value in listOf(5, 10, 15, 25, 40, 60, 120)) { Text("절전 시간 적용") }
                }
            }
            settings.optJSONObject("wakeupConfig")?.let { wake ->
                val current = wake.deviceFlag("headupSwitch"); val currentAngle = wake.deviceInt("headupDegree"); val currentCrown = wake.deviceInt("crownSwitch")
                if (current != null && currentAngle != null && currentCrown != null) {
                    var enabled by remember(current) { mutableStateOf(current) }; var angle by remember(currentAngle) { mutableIntStateOf(currentAngle) }
                    var crown by remember(currentCrown) { mutableIntStateOf(currentCrown) }
                    SettingsSection("깨우기 방식") {
                        SettingsToggle("고개 들면 화면 켜기", enabled, editable) { enabled = it }
                        Text("고개 드는 각도 $angle°"); Slider(angle.toFloat(), { angle = it.toInt() }, valueRange = 0f..90f, enabled = editable)
                        SettingsChoice("다이얼 화면 끄기 애니메이션", crown, listOf(0 to "꺼짐", 1 to "자이로스코프", 2 to "콕 찌르기", 3 to "구슬"), editable) { crown = it }
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.wakeup(enabled, angle, crown) } }, enabled = editable) { Text("깨우기 설정 적용") }
                    }
                }
            }
            settings.optJSONObject("headGestures")?.let { head ->
                val enabledNow = head.deviceFlag("enabled"); val modeNow = head.deviceInt("mode")
                if (enabledNow != null && modeNow != null) {
                    var enabled by remember(enabledNow) { mutableStateOf(enabledNow) }; var mode by remember(modeNow) { mutableIntStateOf(modeNow) }
                    SettingsSection("머리 제스처 제어") {
                        SettingsToggle("머리 제스처 허용", enabled, editable) { enabled = it }
                        SettingsChoice("기기 모드", mode, listOf(0 to "모드 0", 1 to "모드 1"), editable) { mode = it }
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.headGestures(enabled, mode) } }, enabled = editable) { Text("머리 제스처 적용") }
                    }
                }
            }
            settings.optJSONObject("crownConfig")?.let { crown ->
                val d = crown.deviceInt("direction"); val c = crown.deviceInt("double"); val l = crown.deviceInt("longPress")
                if (d != null && c != null && l != null) {
                    var direction by remember(d) { mutableIntStateOf(d) }; var double by remember(c) { mutableIntStateOf(c) }; var long by remember(l) { mutableIntStateOf(l) }
                    val choices = listOf(0 to "어시스턴트", 1 to "홈", 2 to "방해 금지", 3 to "녹음", 4 to "자막", 5 to "프롬프터", 6 to "종일 기록", 7 to "없음", 8 to "할 일", 9 to "실시간 힌트")
                    SettingsSection("다이얼 조작") {
                        SettingsChoice("방향", direction, listOf(0 to "방향 0", 1 to "방향 1"), editable) { direction = it }
                        SettingsChoice("두 번 누르기", double, choices, editable) { double = it }
                        SettingsChoice("길게 누르기", long, choices.filter { it.first != 6 }, editable) { long = it }
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.crown(direction, double, long) } }, enabled = editable) { Text("다이얼 조작 적용") }
                    }
                }
            }
            settings.optJSONArray("supportedLanguages")?.let { languages ->
                val choices = (0 until languages.length()).map { languages.getString(it) }
                var language by remember(settings.optString("currentLang")) { mutableStateOf(settings.optString("currentLang")) }
                if (choices.isNotEmpty() && language.isNotBlank()) SettingsSection("시스템 언어") {
                    choices.forEach { option -> FilterChip(language == option, { language = option }, label = { Text(option) }, enabled = editable) }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.language(language) } }, enabled = editable) { Text("언어 적용") }
                }
            }
            settings.optJSONObject("privacyConfig")?.let { privacy ->
                val labels = linkedMapOf("mic_switch" to "안경 마이크", "led_light" to "표시등", "led_auto_light" to "표시등 자동 밝기", "log_switch" to "기기 로그")
                val values = remember(privacy.toString()) { mutableStateMapOf<String, Boolean>().apply { labels.keys.forEach { key -> privacy.deviceFlag(key)?.let { put(key, it) } } } }
                if (values.isNotEmpty()) SettingsSection("개인정보 및 하드웨어") {
                    labels.forEach { (key, label) -> values[key]?.let { SettingsToggle(label, it, editable) { values[key] = it } } }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.privacy(values.toMap()) } }, enabled = editable) { Text("개인정보 설정 적용") }
                }
            }
            settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("voiceWakeup")?.let { current ->
                var enabled by remember(current) { mutableStateOf(current) }
                SettingsSection("음성 깨우기") {
                    SettingsToggle("안경 어시스턴트 깨우기 허용", enabled, editable) { enabled = it }
                    Note("이 항목은 안경의 깨우기 스위치를 변경합니다. 응답 방식은 '음성 및 프롬프트'에서 설정합니다.")
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.voiceWake(enabled) } }, enabled = editable) { Text("음성 깨우기 적용") }
                }
            }
            val units = settings.optJSONObject("systemUnitsConfig")
            val currentTime = units?.deviceInt("time_format")
            val currentTemp = units?.deviceInt("temp_unit")
            val currentMeasure = units?.deviceInt("measurement")
            if (currentTime != null && currentTemp != null && currentMeasure != null) {
                var time by remember(currentTime) { mutableIntStateOf(currentTime) }
                var temperature by remember(currentTemp) { mutableIntStateOf(currentTemp) }
                var measurement by remember(currentMeasure) { mutableIntStateOf(currentMeasure) }
                SettingsSection("시간 및 단위") {
                    SettingsChoice("시간", time, listOf(1 to "24시간제", 2 to "12시간제"), editable) { time = it }
                    SettingsChoice("온도", temperature, listOf(1 to "섭씨 °C", 2 to "화씨 °F"), editable) { temperature = it }
                    SettingsChoice("측정 단위", measurement, listOf(1 to "미터법", 2 to "야드파운드법"), editable) { measurement = it }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.units(time, temperature, measurement) } },
                        enabled = editable && listOf(time, temperature, measurement).all { it in 1..2 }) { Text("시간 및 단위 적용") }
                    Note("날짜 형식은 안경의 현재 설정을 따릅니다.")
                }
            }
            if (settings.optJSONObject("voiceAssistantConfig") == null || units == null) {
                Note("음성 깨우기와 단위 옵션은 기기가 해당 설정을 보고한 뒤에 나타납니다. 보고되지 않은 설정은 기본값으로 덮어쓰지 않습니다.")
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingsToggle(title: String, value: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(value, update, enabled = enabled)
    }
}

@Composable
private fun SettingsChoice(title: String, value: Int, choices: List<Pair<Int, String>>, enabled: Boolean, update: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolOutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$title: ${choices.firstOrNull { it.first == value }?.second ?: "기기 값 $value"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { update(id); expanded = false }) }
        }
    }
}
