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
internal fun KoreanWakeScreen(model: AppModel, voiceSettings: () -> Unit, deviceSettings: () -> Unit) {
    val s = model.state
    val linked = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val localReady = SpeechRounds.model(model.config) != null && SpeechRounds.ready(model.getApplication<android.app.Application>(), model.config)
    val busy = s.translationActive || s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending ||
        s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null || s.teleprompter.active != null ||
        s.teleprompter.busy || s.nativeWakeAvailable || s.phoneRunning || s.computerRunning
    val savedOptions = runCatching { KoreanWakeOptions.from(model.config.optString("wakePhrases"), model.config.optString("wakeSeconds"), model.config.optString("wakeFollowSeconds")) }
    val options = savedOptions.getOrElse { KoreanWakeOptions.parse() }
    var phrases by remember(model.config) { mutableStateOf(options.phrases.joinToString(", ")) }
    var listen by remember(model.config) { mutableIntStateOf(options.listenSeconds) }
    var followup by remember(model.config) { mutableIntStateOf(options.followupSeconds) }
    val parsed = runCatching { KoreanWakeOptions.parse(phrases, listen, followup) }
    val dirty = savedOptions.isFailure || parsed.getOrNull() != options
    val editable = !s.wakeActive && !s.wakePending
    var allowAI by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf(false) }
    val context = model.getApplication<android.app.Application>()
    val kws = KoreanKeywordModels.model
    val keywordReady = kws.installed(context) && !kws.downloading
    var confirm by remember { mutableStateOf(false) }
    var confirmNative by remember { mutableStateOf(false) }
    var confirmAmbient by remember { mutableStateOf(false) }
    var startContinuous by remember { mutableStateOf<ConversationSource?>(null) }
    var resumeAfterLink by remember { mutableStateOf(false) }
    val fast = model.config.optBoolean("fastRealtimeHints")
    val speechReady = SpeechRounds.ready(model.getApplication<android.app.Application>(), model.config)
    val continuous = model.config.optBoolean("ambientWakeEnabled")
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("호출 연결 진단", style = MaterialTheme.typography.titleMedium)
            val firmware = s.deviceSettings.settings.optJSONObject("voiceAssistantConfig")
            val mic = s.deviceSettings.settings.optJSONObject("privacyConfig")?.deviceFlag("mic_switch")
            fun flag(value: Boolean?) = when (value) { true -> "켜짐"; false -> "꺼짐"; null -> "기기 보고 없음" }
            Note("안경 연결: " + if (linked) "완전 연결" else s.glasses.link.detail)
            Note("안경 마이크: " + flag(mic))
            Note("펌웨어 호출: " + flag(firmware?.deviceFlag("voiceWakeup")) + " · " + when (firmware?.deviceInt("wakeupWord")) { 1 -> "샤오레이 샤오레이"; 2 -> "Hey RayNeo"; else -> "호출어 미확인" })
            Note("앱 자동 응답: " + flag(model.config.optBoolean("nativeAutoAnswer")))
            Note("최근 기본 호출: " + s.nativeWakeStatus)
            Note("음성 모델: " + if (speechReady && s.conversation.modelReady) "준비됨" else "설정 또는 다운로드 필요")
            Note("기록 오디오: ${s.conversation.decodedSeconds.toInt()}초 · " + (s.conversation.error ?: s.conversation.status))
            Note(s.audioLevels.timingSummary())
            Note(s.captureTimingStatus)
            Note("휴대폰의 실제 수신·디코딩·전사 시각입니다. 첫 발화까지의 대기와 무음·펌웨어 묶음 전송을 포함하며 물리적 발화→PCM 지연은 아닙니다. 디코더를 다시 시작하면 해당 디코더 측정은 초기화됩니다. 원문이나 음성은 진단에 추가 저장하지 않습니다.")
            Note("AI 연결: " + s.agentServer.receiverStatus)
            ToolOutlinedButton(onClick = deviceSettings) { Text("안경 마이크·기본 호출 설정") }
            if (!model.config.optBoolean("nativeAutoAnswer")) ToolOutlinedButton(onClick = { confirmNative = true }, enabled = model.recommendedEditable) { Text("앱 기본 호출 자동 응답 켜기") }
            if (!speechReady) ToolOutlinedButton(onClick = voiceSettings) { Text("음성 인식 설정") }
        } }
        item { ContentCard {
            Text("종일 및 실시간 한국어 호출", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(continuous, { enabled -> if (enabled) confirmAmbient = true else model.saveConfig(mapOf("ambientWakeEnabled" to "false")) }, enabled = model.recommendedEditable)
                Text("기록 중 호출어 감지 및 AI 질문 허용")
            }
            if (continuous) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(model.config.optBoolean("ambientWakeContext"), { enabled -> model.saveConfig(mapOf("ambientWakeContext" to enabled.toString())) })
                    Text("명시 호출 질문의 짧은 대화 맥락 사용")
                }
                Note("같은 기록·식별된 화자(또는 실시간 본인 채널)의 최근 질문/답변 최대 3개를 2분 동안 다음 명시 호출에 함께 보냅니다. 화자 미확인 시 맥락을 보내지 않습니다. 작업 도구 없음. 호출을 다시 말해야 합니다.")
            }
            Note("종일 기록은 안경의 오디오 전송 및 전사 지연에 의존합니다. 빠른 실시간 힌트는 화면을 유지하는 듀얼 마이크 경로를 사용합니다. 두 경로 모두 전사가 끝난 후 호출을 검사하며 별도 120초 제한은 없습니다. 화자 인증 및 작업 도구는 지원하지 않습니다.")
            val capturing = s.conversation.running && (s.conversation.source == ConversationSource.ALWAYS_ON || (s.conversation.source == ConversationSource.REALTIME && fast))
            Note(if (continuous && capturing) "최근 호출 상태: " + s.ambientWakeStatus else "종일 또는 빠른 실시간 기록을 시작해야 호출을 감지합니다.")
            if (continuous && capturing) TextButton(onClick = { model.attempt { model.service().resetAmbientWake() } }) { Text("AI 질문 취소·대화 맥락 초기화") }
            if (s.alwaysOnResumePending) {
                Note("같은 안경의 자동 재연결 및 이전 기록 저장을 기다리는 중입니다.")
                ToolOutlinedButton(onClick = { model.attempt { model.service().stopConversation() } }) { Text("자동 재개 취소 및 기록 중지") }
            } else if (!s.conversation.running) {
                ToolButton(onClick = { resumeAfterLink = false; startContinuous = ConversationSource.ALWAYS_ON }, enabled = continuous && linked && speechReady && s.conversation.modelReady && !busy && !dirty) { Text("종일 기록 및 호출 시작") }
                ToolOutlinedButton(onClick = { startContinuous = ConversationSource.REALTIME }, enabled = continuous && linked && speechReady && s.conversation.modelReady && !busy && !dirty) { Text("빠른 실시간 호출 시작") }
            } else if (capturing) ToolOutlinedButton(onClick = { model.attempt { model.service().stopConversation() } }, enabled = !s.conversation.stopping) { Text("기록 및 호출 중지") }
        } }
        item { ContentCard {
            Text("한국어 호출 (1회 시험)", style = MaterialTheme.typography.headlineSmall)
            Note(if (s.wakeActive) "호출 대기 중" else if (s.wakePending) "질문 응답 대기 중" else s.wakeResult)
            Note("안경 마이크와 로컬 한국어 음성 인식을 사용합니다. 안경의 내장 깨우기 모델은 바꾸지 않습니다. 한 명령 처리 후 종료합니다.")
            if (s.wakeActive) Note(s.captionStatus)
            Note("현재 호출어: " + options.label + " · " + options.listenSeconds + "초 대기 / 후속 질문 " + options.followupSeconds + "초")
            Note("예: " + options.phrases.first() + ", 지금 몇 시야")

            if (!s.wakeActive && !s.wakePending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keyword, { keyword = it })
                    Text("경량 호출 모델로 먼저 감지 (시험)")
                }
                if (keyword) {
                    Note("한국어 전용 약 9.3MB KWS로 대기하고 후보가 감지되면 기존 로컬 ASR로 호출어를 다시 확인합니다. 확인되지 않은 후보는 AI에 보내지 않습니다. 모델 출처: kairess/ko-kws-zipformer-tiny (Apache-2.0). 실제 감지율과 전력은 미검증입니다.")
                    if (!keywordReady) ToolOutlinedButton(onClick = { kws.download(context.applicationContext) }, enabled = !kws.downloading) {
                        Text(if (kws.downloading) "경량 모델 다운로드 ${(kws.progress * 100).toInt()}%" else "경량 호출 모델 내려받기")
                    }
                    kws.message?.let { Note(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowAI, { allowAI = it })
                    Text("인식된 질문을 연결된 AI로 보내기 허용 (작업 도구 없음)")
                }
                Note("기본은 로컬 명령만 실행합니다. AI를 허용하면 호출 후 인식한 한 문장이 DSH 모델로 전송되며 이용량/비용이 발생할 수 있습니다. 화자 인증은 하지 않습니다.")
                if (!linked) Note("먼저 안경을 연결하세요.")
                if (!localReady) ToolOutlinedButton(onClick = voiceSettings) { Text("로컬 음성 모델 설정") }
                ToolButton(onClick = { confirm = true }, enabled = linked && localReady && !busy && !dirty && (!keyword || keywordReady)) { Text("호출 대기 시작") }
            } else ToolButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }) { Text("호출 중지") }
        } }
        item { ContentCard {
            Text("호출어 및 대기 설정", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(phrases, { phrases = it.take(80) }, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text("나만의 호출어") }, singleLine = true, isError = parsed.isFailure,
                supportingText = { Text(parsed.exceptionOrNull()?.message ?: "한글 2~12자, 최대 3개 · 쉼표로 구분") })
            Text("호출 대기 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KoreanWakeOptions.listenChoices.forEach { n ->
                    FilterChip(selected = listen == n, onClick = { listen = n }, label = { Text(n.toString() + "초") }, enabled = editable)
                }
            }
            Text("호출 후 질문 대기 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KoreanWakeOptions.followupChoices.forEach { n ->
                    FilterChip(selected = followup == n, onClick = { followup = n }, label = { Text(n.toString() + "초") }, enabled = editable)
                }
            }
            if (savedOptions.isFailure) Note("기존 호출 설정을 읽을 수 없습니다. 올바른 설정을 다시 저장하세요.")
            if (dirty) Note("호출을 시작하기 전에 변경 내용을 저장하세요.")
            if (!editable) Note("호출을 중지한 후 변경할 수 있습니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(onClick = {
                    val value = parsed.getOrNull() ?: return@ToolButton
                    model.saveConfig(mapOf("wakePhrases" to value.phrases.joinToString(", "), "wakeSeconds" to listen.toString(), "wakeFollowSeconds" to followup.toString()))
                }, enabled = editable && parsed.isSuccess && dirty) { Text("설정 저장") }
                TextButton(onClick = { phrases = KoreanWakeOptions.DEFAULT_PHRASES; listen = 120; followup = 12 }, enabled = editable) { Text("기본값 복원") }
            }
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
    if (confirmNative || confirmAmbient) AlertDialog(
        onDismissRequest = { confirmNative = false; confirmAmbient = false },
        title = { Text(if (confirmNative) "기본 AI 호출 허용" else "기록 중 AI 호출 허용") },
        text = { Text("호출 뒤 인식된 질문이 연결된 AI에 전송될 수 있으며 이용량이나 비용이 발생할 수 있습니다. 녹음 시작과 업무 승인은 별도입니다.") },
        confirmButton = { TextButton(enabled = model.recommendedEditable, onClick = {
            val key = if (confirmNative) "nativeAutoAnswer" else "ambientWakeEnabled"
            confirmNative = false; confirmAmbient = false
            model.saveConfig(mapOf(key to "true"))
        }) { Text("동의하고 켜기") } },
        dismissButton = { TextButton(onClick = { confirmNative = false; confirmAmbient = false }) { Text("취소") } })
    startContinuous?.let { source -> AlertDialog(onDismissRequest = { startContinuous = null }, title = { Text(if (source == ConversationSource.REALTIME) "빠른 실시간 호출을 시작할까요?" else "종일 기록 및 호출을 시작할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("안경 마이크의 음성이 인식·기록되며 보존 설정에 따라 원본이 저장될 수 있습니다. 설정된 AI 서비스에 전사가 전달될 수 있습니다. 참여자 동의와 장소 보안 규칙을 확인하세요." + if (source == ConversationSource.REALTIME) " 빠른 스트리밍 설정(도구 없는 힌트)을 켜고 실시간 화면을 사용합니다." else "")
                if (source == ConversationSource.ALWAYS_ON) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(resumeAfterLink, { resumeAfterLink = it })
                        Text("이번 기록만 같은 안경 재연결 시 자동 재개")
                    }
                    Note("이탈 시간이 길어도 재개합니다. 직접 중지·연결 해제·서비스 종료 시 동의가 취소됩니다. 재부팅 후 자동 기록하지 않습니다.")
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            startContinuous = null
            if (source == ConversationSource.REALTIME && !fast) model.saveConfig(mapOf("fastRealtimeHints" to "true"))
            if (source != ConversationSource.REALTIME || model.config.optBoolean("fastRealtimeHints")) model.attempt { model.service().startConversation(ConversationMode.DAILY, source, resumeOnReconnect = source == ConversationSource.ALWAYS_ON && resumeAfterLink) }
        }, enabled = continuous && linked && speechReady && !busy && !dirty) { Text("동의하고 시작") } },
        dismissButton = { TextButton(onClick = { startContinuous = null }) { Text("취소") } }) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("한국어 호출 대기를 시작할까요?") },
        text = { Text("주변 음성이 일시적으로 로컬에서 인식됩니다. 기록을 저장하지 않습니다. 참여자 동의와 장소 보안 규칙을 확인하세요." + if (allowAI) " 호출 후 질문 1개를 설정된 AI로 보냅니다." else " AI 요청은 하지 않습니다.") },
        confirmButton = { TextButton(enabled = linked && localReady && !busy && !dirty && (!keyword || keywordReady), onClick = {
            confirm = false; model.attempt { model.service().startKoreanWake(allowAI, keyword) }
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
