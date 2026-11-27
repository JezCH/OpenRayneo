// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun PersonalizationScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AboutYouSection()
        HorizontalDivider()
        Text("자동 기억 참조", style = MaterialTheme.typography.titleMedium)
        Note("일반 질문에 자동으로 참고할 개인화 정보 및 기록 범위를 선택합니다.")
        OrdinaryContextButton()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalClockScreen(model: AppModel) {
    val s = model.state
    val connected = s.available && s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    var minutes by rememberSaveable { mutableStateOf("5") }
    var seconds by rememberSaveable { mutableStateOf("0") }
    val duration = minutes.toLongOrNull()?.takeIf { it in 0..1440 }?.let { m ->
        seconds.toLongOrNull()?.takeIf { it in 0..59 }?.let { sec -> (m * 60 + sec) * 1000 }
    }?.takeIf { it in 1000..LocalClockPolicy.MAX_TIMER_MS }
    var readout by remember { mutableStateOf<Pair<String, String>?>(null) }
    var result by remember { mutableStateOf("") }
    LaunchedEffect(connected) {
        if (!connected) { readout = null; result = ""; return@LaunchedEffect }
        while (true) {
            readout = runCatching { model.service().clockReadout() }.getOrNull()
            delay(1000)
        }
    }
    fun command(kind: LocalClockPolicy.Kind, ms: Long? = null) {
        model.attempt {
            result = model.service().controlLocalClock(LocalClockPolicy.Command(kind, ms))
            readout = model.service().clockReadout()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(deviceConnectionStatus(s).label, style = MaterialTheme.typography.labelLarge)
        Note("기존 음성 명령과 같은 휴대폰 타이머·스톱워치를 사용합니다. 별도 AI 요청이나 녹음은 없습니다.")
        if (!connected) Note("안경을 완전히 연결한 뒤 사용할 수 있습니다.")
        OpticalPanel {
            Text("타이머", style = MaterialTheme.typography.titleMedium)
            Text(readout?.first ?: "상태 확인 대기", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("분") }, singleLine = true)
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("초") }, singleLine = true)
            }
            if (duration == null) Note("1초부터 24시간까지 지정하세요.")
            ToolButton(onClick = { command(LocalClockPolicy.Kind.TIMER_START, duration) }, enabled = connected && duration != null, modifier = Modifier.fillMaxWidth()) { Text("타이머 시작") }
            ToolOutlinedButton(onClick = { command(LocalClockPolicy.Kind.TIMER_CANCEL) }, enabled = connected, modifier = Modifier.fillMaxWidth()) { Text("타이머 취소") }
        }
        OpticalPanel {
            Text("스톱워치", style = MaterialTheme.typography.titleMedium)
            Text(readout?.second ?: "상태 확인 대기", style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { command(LocalClockPolicy.Kind.WATCH_START) }, enabled = connected) { Text("시작") }
                TextButton(onClick = { command(LocalClockPolicy.Kind.WATCH_STOP) }, enabled = connected) { Text("정지") }
                TextButton(onClick = { command(LocalClockPolicy.Kind.WATCH_RESET) }, enabled = connected) { Text("초기화") }
            }
        }
        if (result.isNotBlank()) Note(result)
        Note("타이머 완료 알림은 기존 휴대폰/안경 알림 설정을 따릅니다. 서비스 종료·수동 연결 해제·기기 변경 시 초기화됩니다. 절전 중 알림이 늦어질 수 있으므로 중요 안전 알람으로 사용하지 마세요.")
    }
}
