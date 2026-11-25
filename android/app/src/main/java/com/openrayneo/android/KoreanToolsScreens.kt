// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun KoreanWakeScreen(model: AppModel, voiceSettings: () -> Unit) {
    val s = model.state
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val localReady = SpeechRounds.model(model.config) != null && SpeechRounds.ready(model.getApplication<android.app.Application>(), model.config)
    val busy = s.translationActive || s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending ||
        s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null || s.teleprompter.active != null ||
        s.teleprompter.busy || s.nativeWakeAvailable || s.phoneRunning || s.computerRunning
    var allowAI by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("한국어 호출 (실험)", style = MaterialTheme.typography.headlineSmall)
            Note(if (s.wakeActive) "호출 대기 중" else if (s.wakePending) "질문 응답 대기 중" else s.wakeResult)
            Note("안경의 기존 음성 수신과 로컬 한국어 인식을 사용합니다. 내장 깨우기 모델은 변경하지 않습니다. 한 번 시작하면 최대 2분간 듣고 한 명령 처리 후 종료합니다. 호출어 뒤 질문을 한 문장으로 말하거나 12초 이내에 다음 문장으로 말하세요.")
            Note("예: 자비스, 지금 몇 시야 / 레이네오야, 안경 타이머 오 분 시작해줘 / 자비스, 안경 집중 모드 켜줘")
            if (!s.wakeActive && !s.wakePending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowAI, { allowAI = it })
                    Text("인식된 질문을 연결된 AI로 보내기 허용 (작업 도구 없음)")
                }
                Note("기본은 로컬 명령만 실행합니다. AI를 허용하면 호출 후 인식한 한 문장이 DSH 모델로 전송되며 이용량/비용이 발생할 수 있습니다. 화자 인증은 하지 않습니다.")
                if (!linked) Note("먼저 안경을 연결하세요.")
                if (!localReady) ToolOutlinedButton(onClick = voiceSettings) { Text("로컬 음성 모델 설정") }
                ToolButton(onClick = { confirm = true }, enabled = linked && localReady && !busy) { Text("호출 대기 시작") }
            } else ToolButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }) { Text("호출 중지") }
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("한국어 호출 대기를 시작할까요?") },
        text = { Text("주변 음성이 일시적으로 로컬에서 인식됩니다. 기록을 저장하지 않습니다. 참여자 동의와 장소 보안 규칙을 확인하세요." + if (allowAI) " 호출 후 질문 1개를 설정된 AI로 보냅니다." else " AI 요청은 하지 않습니다.") },
        confirmButton = { TextButton(enabled = linked && localReady && !busy, onClick = {
            confirm = false; model.attempt { model.service().startKoreanWake(allowAI) }
        }) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
}

@Composable
internal fun KoreanGlyphScreen(model: AppModel) {
    val s = model.state
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val busy = s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null ||
        s.conversation.running || s.translationActive || s.nativeWakeAvailable || s.teleprompter.active != null || s.teleprompter.busy
    val samples = listOf("안녕하세요. 한국어 표시 테스트", "가나다라마바사아자차카타파하", "각 간 갇 갈 갉 값 갓 갔 강 갖 갗 같 갚 갛",
        "똠 똡 뙈 뛟 뽈 뿔 쀍 쏠 쐐 쫓 쯔 찮 촣 휭 힣", "ㄱ ㄴ ㄷ ㄹ ㅁ ㅂ ㅅ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ", "회의 일정 11월 25일 09:30 · 100%")
    var page by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf(false) }
    val count = 12
    val maxPage = (11172 - 1) / count
    val start = 0xAC00 + page * count
    val sample = if (range) (start..minOf(start + count - 1, 0xD7A3)).joinToString(" ") { it.toChar().toString() } else samples[page.coerceIn(0, samples.lastIndex)]
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ContentCard {
            Text("내장 폰트 확인", style = MaterialTheme.typography.titleMedium)
            Note("실제 안경에서 글자가 빠지거나 네모로 표시되는지 비교합니다. 전송 성공은 글리프 표시 성공을 뜻하지 않습니다. 펌웨어에는 쓰지 않습니다.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(range, { range = it; page = 0 }); Text("한글 완성형 11,172자 페이지 검사") }
            Text(if (range) "U+%04X ~ U+%04X".format(start, minOf(start + count - 1, 0xD7A3)) else "대표 문장 ${page + 1}/${samples.size}")
            Text(sample, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("이전") }
                TextButton(onClick = { page++ }, enabled = page < if (range) maxPage else samples.lastIndex) { Text("다음") }
                ToolButton(onClick = { model.attempt { model.service().sendKoreanGlyphSample(sample) } }, enabled = linked && !busy) { Text("안경에 표시") }
            }
            if (range) {
                var jump by remember { mutableStateOf("") }
                OutlinedTextField(jump, { jump = it.take(4) }, label = { Text("페이지 번호 (1–${maxPage+1})") }, singleLine = true)
                TextButton(onClick = { jump.toIntOrNull()?.takeIf { it in 1..maxPage+1 }?.let { page = it - 1 } }) { Text("이동") }
            }
            Note("정상 표시 여부는 직접 확인해야 합니다. 이 검사는 HUD 알림의 글꼴 경로에 한정됩니다.")
        } }
    }
}
