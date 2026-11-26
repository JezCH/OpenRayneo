// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingScreen(
    model: AppModel,
    actions: UiActions,
    connect: () -> Unit,
    configure: (String) -> Unit,
) {
    val step = model.onboardingStep
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val titles = listOf("OpenRayneo 시작하기", "안경 연결", "음성 인식 준비", "AI 연결 설정", "준비 상태 확인")
    val subtitles =
        listOf(
            "OpenRayneo에 오신 것을 환영합니다. 몇 단계로 설정을 마치거나 나중에 계속할 수 있습니다.",
            "휴대폰으로 RayNeo iO에 직접 연결하면 내비게이션과 답변이 눈앞에 표시됩니다.",
            "한국어 음성 인식 모델을 준비합니다. 기록은 별도로 시작할 때만 실행됩니다.",
            "휴대폰은 로컬 Agent로 작업을 처리하고 독립적으로 기록·요약합니다. 컴퓨터가 온라인이면 작업을 다음에도 맡길 수 있습니다: Codex.",
            "아래는 현재 준비 상태입니다. 완료하지 않은 항목은 언제든 설정에서 계속할 수 있습니다.",
        )
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("OpenRayneo", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = model::finishSetup) { Text("나중에 설정") }
        }
        LinearProgressIndicator(
            progress = { (step + 1) / 5f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "시작하기: · ${step + 1} / 5",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(titles[step], style = MaterialTheme.typography.headlineLarge)
            Note(subtitles[step])
            when (step) {
                0 -> {
                    GlassesArtwork(
                        Modifier.fillMaxWidth().height(90.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    SetupRow(Icons.Outlined.GraphicEq, "일상과 회의 기록", "안경 음성을 인식하고 필요한 정보를 찾아보기")
                    SetupRow(Icons.Outlined.AutoAwesome, "언제든 AI에게 질문", "연결한 AI 서비스 또는 컴퓨터에 작업 요청")
                    SetupRow(Icons.Outlined.Tune, "직접 제어", "표시와 음성 수신은 언제든 일시정지 가능")
                    RecommendedSettingsCard(model)
                }
                1 -> {
                    ContentCard {
                        GlassesArtwork(
                            Modifier.fillMaxWidth().height(110.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (model.state.glasses.link.phase == LinkPhase.FULL) "안경 연결됨"
                            else "연결 전 준비",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Note("1. 안경을 깨워 휴대폰 가까이 두기\n2. 공식 앱이나 컴퓨터와의 안경 연결 끊기\n3. 휴대폰에서 시스템 페어링 안내 확인")
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (model.state.glasses.link.phase == LinkPhase.FULL) "연결 보기"
                                else "안경 연결"
                            )
                        }
                    }
                    Note("'근처 기기' 권한은 연결할 때만 요청합니다. 연결하지 않아도 어시스턴트를 먼저 설정할 수 있습니다.")
                }
                2 -> {
                    ContentCard {
                        Text(if (model.state.conversation.modelReady) "음성 인식 준비됨" else "음성 인식 설정 필요", style = MaterialTheme.typography.titleMedium)
                        Note("한국어 중심이라면 기기 내장 한국어 모델을 먼저 준비하세요. 한영 혼합에는 다국어 모델을 선택할 수 있습니다. 모델을 저장했다고 실제 마이크 인식이 검증된 것은 아닙니다.")
                        ToolButton(onClick = { configure("voice-settings") }, modifier = Modifier.fillMaxWidth()) { Text("음성 인식 설정") }
                        Note("원본 음성 보존 및 AI 전송 범위도 설정에서 확인하세요.")
                    }
                }
                3 -> {
                    SetupOption(
                        "로컬 Agent",
                        "로컬 엔드포인트를 켜고 DSH Go 플러그인에서 안경을 연결하세요.",
                        model.state.agentServer.enabled,
                        Icons.Outlined.Smartphone,
                    ) {
                        configure("agent-api")
                    }
                    SetupOption(
                        "컴퓨터 Codex",
                        "컴퓨터가 생성한 설정 파일을 가져오거나 연결 매개변수를 입력하세요. USB 연동 시 컴퓨터에서 포워딩을 켜야 합니다.",
                        model.config.optString("codexToken").isNotBlank(),
                        Icons.Outlined.Computer,
                    ) {
                        configure("computer")
                    }
                    Note("로컬 Agent의 의미 전략과 모델은 DSH Go에서 설정합니다. 기록 요약 모델은 '음성 및 요약' 설정에서 따로 설정할 수 있습니다. 휴대폰 음성 입력은 휴대폰의 인식 서비스를 사용합니다.")
                }
                4 -> {
                    ContentCard {
                        CompletionRow(
                            "안경 연결",
                            model.state.glasses.link.phase == LinkPhase.FULL,
                            "연결됨",
                            "나중에 연결",
                        )
                        CompletionRow("음성 인식", model.state.conversation.modelReady, "모델 준비됨", "나중에 준비")
                        CompletionRow(
                            "로컬 Agent",
                            model.state.agentServer.enabled,
                            "연결 기능 켜짐 · 응답 미검증",
                            "선택 사항, 나중에 설정",
                        )
                        CompletionRow(
                            "컴퓨터 Codex",
                            model.config.optString("codexToken").isNotBlank(),
                            "설정 저장됨",
                            "선택 사항, 나중에 설정",
                        )
                    }
                    ContentCard {
                        Text("안경 표시 확인", style = MaterialTheme.typography.titleMedium)
                        Note("기기 진단의 테스트 알림을 안경에서 직접 확인하세요. AI 응답 진단은 AI 화면에서 별도로 실행합니다.")
                        ToolOutlinedButton(onClick = { configure("diagnostics") }) { Text("안경 출력 테스트") }
                    }
                    if (!model.backgroundNotifications)
                        ContentCard {
                            Text("백그라운드에서도 연결 상태 확인", style = MaterialTheme.typography.titleMedium)
                            Note("상태 알림을 허용하면 시스템 알림 표시줄에서 안경 연결을 확인하고 서비스를 중지할 수 있습니다.")
                            ToolOutlinedButton(onClick = actions.notificationPermission) {
                                Text("상태 알림 허용")
                            }
                        }
                    Note("'설정 → 사용 안내 다시 보기'에서 언제든 여기로 돌아올 수 있습니다.")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) ToolOutlinedButton(onClick = { model.step(step - 1) }) { Text("이전") }
            ToolButton(
                onClick = { if (step == 4) model.finishSetup() else model.step(step + 1) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                Text(if (step == 0) "설정 시작" else if (step == 4) "OpenRayneo 시작" else "계속")
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(description)
        }
    }
}

@Composable
private fun SetupOption(
    title: String,
    description: String,
    saved: Boolean,
    icon: ImageVector,
    click: () -> Unit,
) {
    ContentCard {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Note(description)
        if (saved) StatusPill("설정 저장됨")
        ToolOutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "설정 보기" else "설정: $title")
        }
    }
}

@Composable
private fun CompletionRow(title: String, done: Boolean, complete: String, incomplete: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint =
                if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Note(if (done) complete else incomplete)
        }
    }
}

@Composable
internal fun SettingsScreen(
    model: AppModel,
    actions: UiActions,
    open: (String) -> Unit,
    replay: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("서비스 연결")
        SettingsRow(
            "기록 요약 모델",
            if (!model.mobileModelConfigured) "아직 설정되지 않음"
            else model.config.optString("modelName").ifBlank { "설정 저장됨" },
            Icons.Outlined.Smartphone,
        ) {
            open("model")
        }
        SettingsRow(
            "컴퓨터 Codex",
            if (model.state.computerConnected) "연결됨" else "연결 매개변수 및 설정 가져오기",
            Icons.Outlined.Computer,
        ) {
            open("computer")
        }
        SettingsRow("DSH Go 및 외부 Agent", "음성 작업 위임, 안경 도구 및 로컬 API", Icons.Outlined.Hub) { open("agent-api") }
        SectionLabel("개인화")
        SettingsRow("호출어 및 대기 시간", "한국어 소프트웨어 호출 설정", Icons.Outlined.Mic) { open("korean-wake") }
        SectionLabel("기록 및 데이터")
        SettingsRow("기존 기록 가져오기", "다른 설치본에서 전사·요약·음성 이전", Icons.Outlined.FolderOpen) { open("record-migration") }
        SectionLabel("권한 및 기기")
        SettingsRow("음성 및 요약", "안경 ASR, 인물 분석 및 기록 요약", Icons.Outlined.Mic) { open("voice-settings") }
        SettingsRow("안경 설정", "밝기, 화면 위치 및 깨우기", Icons.Outlined.Settings) { open("device-settings") }
        SettingsRow("홈 화면 및 콘텐츠", "위젯, 할 일, 일정 및 날씨", Icons.Outlined.Dashboard) { open("content") }
        SettingsRow("프롬프터 원고함", "로컬 원고, 안경 재생 및 제어", Icons.Outlined.Description) { open("teleprompter") }
        SettingsRow("목소리 및 인물", "본인 등록, 연락처 및 목소리 프로필", Icons.Outlined.People) { open("voice-profiles") }
        SettingsRow(
            "근처 기기",
            if (model.bluetoothAllowed) "허용됨" else "안경 연결 시 필요",
            Icons.Outlined.Bluetooth,
        ) {
            actions.appSettings()
        }
        SettingsRow("휴대폰 알림", "앱별 안경 알림 전달 설정", Icons.Outlined.NotificationsNone) { open("phone-notifications") }
        SettingsRow(
            "알림 접근 권한",
            if (model.notificationAllowed) "허용됨 · ${model.state.notificationReadStatus}"
            else "내비게이션 추종 시 필요",
            Icons.Outlined.NotificationsNone,
            actions.notificationAccess,
        )
        SettingsRow(
            "백그라운드 상태 알림",
            if (model.backgroundNotifications) "허용됨" else "선택 사항, 백그라운드 연결 상태 확인",
            Icons.Outlined.NotificationsActive,
        ) {
            if (model.backgroundNotifications) actions.appSettings()
            else actions.notificationPermission()
        }
        SettingsRow("기기 및 진단", "연결 로그, 테스트 알림 및 오디오 복구", Icons.Outlined.Tune) { open("diagnostics") }
        SectionLabel("화면 모드")
        ContentCard {
            listOf("system" to "시스템 설정 따르기", "light" to "라이트", "dark" to "다크").forEach { (key, title) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = model.appearance == key,
                        onClick = { model.changeAppearance(key) },
                    )
                    TextButton(onClick = { model.changeAppearance(key) }) { Text(title) }
                }
            }
        }
        SectionLabel("정보: OpenRayneo")
        SettingsRow("사용 안내 다시 보기", "안경 연결, 내비게이션 데모 및 어시스턴트 설정", Icons.Outlined.Explore, replay)
        Note(
            "OpenRayneo ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.GIT_COMMIT} (${BuildConfig.VERSION_CODE})\n기록 오디오는 휴대폰에 암호화 저장되며 전사와 화자 분석이 끝난 뒤 전달됩니다. 시스템 음성 입력은 휴대폰 인식 서비스를 사용합니다.",
            Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, icon: ImageVector, click: () -> Unit) {
    Card(
        onClick = click,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
        )
    }
}

@Composable
internal fun ConfigurationScreen(model: AppModel, mobile: Boolean, importConfig: () -> Unit) {
    val urlKey = if (mobile) "modelUrl" else "codexUrl"
    val nameKey = if (mobile) "modelName" else "codexCwd"
    val secretKey = if (mobile) "modelKey" else "codexToken"
    val url = model.formDrafts[urlKey].orEmpty()
    val name = model.formDrafts[nameKey].orEmpty()
    val secret = model.formDrafts[secretKey].orEmpty()
    var showSecret by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val localModel = mobile && MobileModelEndpoint.isLocalAddress(url)
    val ready = url.isNotBlank() && (secret.isNotBlank() || localModel) && (!mobile || name.isNotBlank())
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            if (mobile) Icons.Outlined.Smartphone else Icons.Outlined.Computer,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(if (mobile) "기록 요약 모델 설정" else "컴퓨터 연결 설정", style = MaterialTheme.typography.headlineMedium)
        Note(
            if (mobile) "앱 내 기록 요약에 사용되며 Chat Completions 호환 클라우드 또는 휴대폰 로컬 서비스를 지원합니다. 로컬 서비스에 인증이 없으면 API Key를 비워 둘 수 있습니다."
            else "먼저 컴퓨터에서 OpenRayneo의 Codex 연결 스크립트를 실행한 뒤 생성된 설정을 가져오세요. USB 모드는 포트 포워딩도 켜야 합니다."
        )
        if (!mobile) {
            ToolButton(onClick = importConfig, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("컴퓨터 설정 파일 가져오기")
            }
            Note("OpenRayneo-setup.json을 선택하세요. 액세스 자격 증명이 들어 있으므로 가져온 뒤 다운로드 폴더의 사본을 삭제하세요.")
            HorizontalDivider()
            Text("또는 직접 입력", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            url,
            { model.formDrafts[urlKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "서비스 주소" else "컴퓨터 서비스 주소") },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            placeholder = { Text(if (mobile) "https://서비스 제공자/v1" else "ws://127.0.0.1:4500") },
            singleLine = true,
            supportingText = {
                Text(if (mobile) "원격은 HTTPS 사용, 휴대폰 로컬은 HTTP 가능. 키를 주소에 넣지 마세요" else "USB: 로컬 주소, 무선 연결: 다음 사용 WSS")
            },
        )
        OutlinedTextField(
            name,
            { model.formDrafts[nameKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "모델 이름" else "컴퓨터 작업 디렉터리 (선택)") },
            singleLine = true,
        )
        OutlinedTextField(
            secret,
            { model.formDrafts[secretKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile && localModel) "API Key (로컬 서비스는 비워 둘 수 있음)" else if (mobile) "API Key" else "연결 토큰") },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            visualTransformation =
                if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(
                        if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (showSecret) "키 숨기기" else "키 보기",
                    )
                }
            },
        )
        ToolButton(
            onClick = {
                model.saveConfig(
                    if (mobile) mapOf("modelUrl" to url, "modelName" to name, "modelKey" to secret)
                    else mapOf("codexUrl" to url, "codexCwd" to name, "codexToken" to secret)
                )
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("설정 저장")
        }
        Note("자격 증명은 Android Keystore로 암호화 저장됩니다. 저장해도 모델 요청이나 컴퓨터 작업은 실행되지 않으며 연결 결과는 어시스턴트 탭에서 확인하세요.")
        if (!mobile)
            ContentCard {
                Text("USB 연결 단계", style = MaterialTheme.typography.titleMedium)
                Note(
                    "1. 컴퓨터에 Codex CLI 설치 및 로그인\n2. 저장소의 scripts/phone-codex.py 실행\n3. 컴퓨터에서 adb reverse tcp:4500 tcp:4500 실행\n4. 어시스턴트 탭에서 컴퓨터 Codex를 선택하고 연결 누르기"
                )
            }
    }
}

@Composable
internal fun DiagnosticsScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current
    val s = model.state
    var bleOnly by rememberSaveable { mutableStateOf(false) }
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContentCard {
            Text("알림 읽기", style = MaterialTheme.typography.titleMedium)
            Note(if (model.notificationAllowed) "시스템 권한 허용됨" else "시스템 권한 미허용")
            Note(s.notificationReadStatus)
            Note("현재 길 안내 후보: ${s.navigationEntries.size}개")
            if (s.notificationSnapshotAt > 0)
                Note(
                    "최근 스냅샷 동기화: " +
                        java.text
                            .SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(s.notificationSnapshotAt))
                )
            ToolOutlinedButton(
                onClick = {
                    model.attempt {
                        NavigationNotifications.requestConnection(model.getApplication())
                    }
                },
                enabled = model.notificationAllowed,
            ) {
                Text("알림 읽기 복구")
            }
        }
        ContentCard {
            Text(model.deviceName, style = MaterialTheme.typography.titleLarge)
            Note(s.glasses.link.detail)
            Note(s.glasses.displayDescription)
            Note(s.glasses.audio.detail)
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().connection.sendTestNotification() } },
                enabled = ready,
            ) {
                Text("테스트 알림 보내기")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().retryAudio() } },
                enabled =
                    s.glasses.session?.phase in
                        setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY),
            ) {
                Text("오디오 디코딩 재시작 (페이지 유지)")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().disconnect() } },
                enabled = ready || s.glasses.link.phase == LinkPhase.CONNECTING,
            ) {
                Text("안경 연결 해제")
            }
        }
        ContentCard {
            Text("고급 연결 진단", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(bleOnly, { bleOnly = it }, enabled = !ready)
                Text("BLE 진단 전용", Modifier.padding(start = 12.dp))
            }
            Note("연결 문제 해결 전용입니다. 내비게이션과 어시스턴트 표시는 완전한 연결이 필요합니다.")
            if (bleOnly) {
                ToolOutlinedButton(onClick = actions.scan, enabled = !ready) { Text("기기 스캔") }
                s.devices.forEach { device ->
                    TextButton(onClick = { model.connect(device, true) }, enabled = !ready) {
                        Text("진단 연결 ${device.name}")
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("연결 로그")
            TextButton(
                onClick = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(
                            ClipData.newPlainText("OpenRayneo", s.logs.joinToString("\n"))
                        )
                    model.notice = "로그가 복사됨"
                }
            ) {
                Text("로그 복사")
            }
        }
        ContentCard { Note(s.logs.joinToString("\n").ifBlank { "연결 로그 없음" }) }
    }
}
