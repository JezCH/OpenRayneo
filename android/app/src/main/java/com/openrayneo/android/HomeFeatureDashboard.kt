// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RecommendedSettingsCard(model: AppModel) {
    if (!model.recommendedPending) return
    var confirm by remember { mutableStateOf(false) }
    ContentCard {
        Text("권장 AI 비서 설정", style = MaterialTheme.typography.titleMedium)
        Note("아직 지정하지 않은 호출·후속 대화·빠른 힌트 설정을 한 번에 준비합니다. 이미 지정한 ON/OFF와 모델은 유지합니다.")
        ToolButton(onClick = { confirm = true }, enabled = model.recommendedEditable,
            modifier = Modifier.fillMaxWidth()) { Text("권장 설정 적용") }
        Note("녹음 또는 AI 요청을 지금 시작하지 않습니다. 장기 대화 공유는 AI 화면에서 별도 동의합니다.")
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("권장 설정을 적용할까요?") },
        text = { Text("미설정 항목에 한해 안경 기본 호출 자동 응답, 기록 중 한국어 호출, 명시 호출의 짧은 대화 맥락 및 빠른 힌트를 허용합니다. 연결된 AI에 질문이나 힌트가 전송될 수 있으며 이용량이 발생할 수 있습니다. 실제 녹음은 직접 시작해야 하고 업무 승인은 유지됩니다.") },
        confirmButton = { TextButton(enabled = model.recommendedEditable, onClick = { confirm = false; model.applyRecommendedSettings() }) { Text("동의하고 적용") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("나중에") } })
}

@Composable
private fun FeatureStateRow(title: String, status: FeatureStatusPolicy.Status, action: String, onAction: () -> Unit,
                            checked: Boolean? = null, enabled: Boolean = true, change: ((Boolean) -> Unit)? = null) {
    val phase = status.phase
    val color = when (phase) {
        FeatureStatusPolicy.Phase.ERROR -> MaterialTheme.colorScheme.error
        FeatureStatusPolicy.Phase.BLOCKED, FeatureStatusPolicy.Phase.SETUP -> MaterialTheme.colorScheme.tertiary
        FeatureStatusPolicy.Phase.ACTIVE -> OpticalActiveInk()
        FeatureStatusPolicy.Phase.READY -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(phase.label + " · " + status.detail, color = color, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action) }
        }
        if (checked != null && change != null) Switch(checked, change, enabled = enabled)
    }
}

@Composable
internal fun HomeFeatureDashboard(model: AppModel, navigate: (Int) -> Unit, open: (String) -> Unit, connect: () -> Unit) {
    val s = model.state
    val c = s.conversation
    val cfg = model.config
    val linked = s.available && s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val mic = s.deviceSettings.settings.optJSONObject("privacyConfig")?.deviceFlag("mic_switch")
    val firmwareWake = s.deviceSettings.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("voiceWakeup")
    val speechReady = c.modelReady && model.speechConfigured
    val ai = model.aiConfigured
    val receiver = model.aiReady
    val aiSetup = model.aiStatus
    val aiRoute = if (model.aiLegacy) "agent-api" else "model"
    val ambient = cfg.optBoolean("ambientWakeEnabled")
    val fast = cfg.optBoolean("fastRealtimeHints")
    val native = cfg.optBoolean("nativeAutoAnswer")
    val receiving = s.audioLevels.coordinatorState().phase == AudioPhase.RECEIVING
    val capturesAmbient = c.running && !c.reprocessing && !c.stopping && c.mode != ConversationMode.ENROLL &&
        (c.source == ConversationSource.ALWAYS_ON || (c.source == ConversationSource.REALTIME && fast))
    val captureBusy = c.running || c.stopping || s.alwaysOnResumePending || s.wakeActive || s.wakePending ||
        s.nativeWakeAvailable || s.translationActive || s.captionActive || s.mediaCaption.busy || s.teleprompter.busy || s.teleprompter.active != null ||
        s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null || s.glasses.navigation.enabled ||
        s.phoneRunning || s.computerRunning
    val editing = s.available && !captureBusy && !s.migration.busy
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun toggle(key: String, label: String, value: Boolean, external: Boolean = false) {
        if (value && external) pending = key to label
        else model.saveConfig(mapOf(key to value.toString()))
    }
    val nativeStatus = FeatureStatusPolicy.resolve(native, linked && speechReady && ai && receiver && mic == true && firmwareWake == true,
        active = native && speechReady && receiver && s.nativeWakeAvailable && c.running && !c.stopping && c.source == ConversationSource.NATIVE,
        waiting = true, setupDetail = when {
            !linked -> "안경 연결 필요"
            mic != true || firmwareWake != true -> "안경 마이크·기본 호출 설정 확인"
            !speechReady -> "기록 엔진·음성 인식 설정 확인"
            else -> aiSetup
        }, activeDetail = "안경 기본 호출 음성 처리 · " + model.aiStatus, waitingDetail = "기본 펌웨어 호출 대기")
    val ambientStatus = FeatureStatusPolicy.resolve(ambient, linked && speechReady && ai && receiver && mic != false,
        active = ambient && capturesAmbient && speechReady && ai && receiver && mic != false && c.error == null && receiving,
        waiting = true, blocker = if (mic == false) "안경 마이크 꺼짐" else null,
        error = if (ambient && capturesAmbient) c.error else null,
        setupDetail = when { !linked -> "안경 연결 필요"; !speechReady -> "기록 엔진·음성 인식 설정 확인"; else -> aiSetup },
        activeDetail = "기록 중 호출 검사 · " + s.ambientWakeStatus + " · " + model.aiStatus,
        waitingDetail = if (capturesAmbient) "전사/음성 수신 대기" else "종일 또는 빠른 실시간 기록 시작 필요")
    val hintStatus = FeatureStatusPolicy.resolve(fast, linked && speechReady && ai && receiver,
        active = fast && c.running && !c.reprocessing && c.mode != ConversationMode.ENROLL && c.source == ConversationSource.REALTIME && !c.stopping && !s.focusMode && s.appNotificationsEnabled && speechReady && ai && receiver && mic != false && c.error == null && s.glasses.audio.phase == AudioPhase.RECEIVING,
        waiting = true, blocker = when { s.focusMode -> "집중 모드"; !s.appNotificationsEnabled -> "앱 알림 꺼짐"; mic == false -> "안경 마이크 꺼짐"; else -> null },
        setupDetail = when { !linked -> "안경 연결 필요"; !speechReady -> "기록 엔진·음성 인식 설정 확인"; else -> aiSetup },
        activeDetail = "빠른 실시간 힌트 경로 · " + model.aiStatus, waitingDetail = "실시간 기록 시작 필요")
    OpticalPanel {
        Text("기능 제어판", style = MaterialTheme.typography.titleMedium)
        Note("스위치는 사용 허용 설정입니다. 실제 실행 여부와 필요한 준비는 아래 상태에서 확인하세요.")
        Note("선택 AI: " + AiModelSelection.label(cfg) + " · " + selectedAiStatus(model).label)
        if (!editing && captureBusy) Note("음성 기능을 사용 중입니다. 호출·힌트 설정을 바꾸려면 먼저 종료하세요.")
        FeatureStateRow("기본 AI 호출", nativeStatus,
            if (!linked) "안경 연결" else if (!speechReady) "음성 설정" else if (!ai || !receiver) "AI 연결 상태" else "기기·호출 설정",
            { if (!linked) connect() else if (!speechReady) open("voice-settings") else if (!ai || !receiver) open(aiRoute) else open("korean-wake") },
            native, editing) { toggle("nativeAutoAnswer", "기본 AI 호출", it, true) }
        HorizontalDivider()
        FeatureStateRow("한국어 호출", ambientStatus, "호출·기록 제어", { open("korean-wake") },
            ambient, editing) { toggle("ambientWakeEnabled", "기록 중 한국어 호출", it, true) }
        HorizontalDivider()
        FeatureStateRow("빠른 AI 힌트", hintStatus, "기록 제어", { navigate(2) },
            fast, editing) { toggle("fastRealtimeHints", "빠른 AI 힌트", it, true) }
        HorizontalDivider()
        FeatureStateRow("기억 추천", FeatureStatusPolicy.resolve(cfg.optBoolean("memorySuggestOnSend"), s.available,
            blocker = if (s.migration.busy) "기록 이전 중" else null, readyDetail = "질문 작성 중 기기 내 검색"),
            "기억·AI 질문", { navigate(1) }, cfg.optBoolean("memorySuggestOnSend"), s.available) {
            toggle("memorySuggestOnSend", "기억 추천", it)
        }
        HorizontalDivider()
        FeatureStateRow("대화 이어가기", FeatureStatusPolicy.resolve(s.chatContinuity.enabled, s.available && receiver,
            error = s.chatContinuityError.takeIf { it.isNotBlank() }, readyDetail = "저장된 일반 대화 " + s.chatContinuity.turns.size + "턴",
            setupDetail = "AI 연결 필요"), "공유·기억 관리", { navigate(1) })
        HorizontalDivider()
        val recordStatus = recording(s)
        FeatureStateRow("일상·회의 기록", if (c.running || c.stopping || s.alwaysOnResumePending)
            FeatureStatusPolicy.Status(when (recordStatus.phase) {
                SystemStatusPolicy.Phase.ACTIVE -> FeatureStatusPolicy.Phase.ACTIVE
                SystemStatusPolicy.Phase.BUSY -> FeatureStatusPolicy.Phase.STOPPING
                SystemStatusPolicy.Phase.ERROR -> FeatureStatusPolicy.Phase.ERROR
                SystemStatusPolicy.Phase.BLOCKED -> FeatureStatusPolicy.Phase.BLOCKED
                else -> FeatureStatusPolicy.Phase.WAITING
            }, recordStatus.label)
        else FeatureStatusPolicy.resolve(true, linked && speechReady,
            blocker = if (captureBusy) "다른 기능 사용 중" else null, error = c.error,
            setupDetail = if (!linked) "안경 연결 필요" else "음성 설정 확인",
            readyDetail = "직접 시작할 때만 기록"), "기록 관리", { navigate(2) })
        HorizontalDivider()
        FeatureStateRow("휴대폰 알림", FeatureStatusPolicy.resolve(s.appNotificationsEnabled, linked && model.notificationAllowed && s.notificationConnected,
            blocker = if (s.focusMode) "집중 모드" else null,
            setupDetail = when { !linked -> "안경 연결 필요"; !model.notificationAllowed -> "알림 접근 권한 필요"; else -> "알림 리스너 연결 대기" },
            readyDetail = "허용된 앱의 알림 전달 대기"), "앱별 알림 설정", { open("phone-notifications") },
            s.appNotificationsEnabled, linked && s.available) { enabled ->
            model.attempt { model.service().applyFocusCommand(if (enabled) FocusPolicy.Command.NOTIFICATIONS_ON else FocusPolicy.Command.NOTIFICATIONS_OFF) }
        }
    }
    pending?.let { (key, label) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(label + " 허용") },
            text = { Text("실제 사용 조건이 충족되면 설정한 AI에 질문 또는 힌트가 전송될 수 있습니다. 이용량이 발생할 수 있으며 녹음 시작과 업무 승인은 별도입니다.") },
            confirmButton = { TextButton(enabled = editing, onClick = { pending = null; model.saveConfig(mapOf(key to "true")) }) { Text("동의하고 켜기") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } })
    }
}
