// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
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
            "음성 모델은 설치 앱에 포함됩니다. 기록은 직접 시작할 때만 실행됩니다.",
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
                "설정 ${step + 1} / 5",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(titles[step], style = MaterialTheme.typography.headlineLarge)
            Note(subtitles[step])
            when (step) {
                0 -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OpticalSignature(Modifier.size(120.dp))
                    }
                    SetupRow(Icons.Outlined.GraphicEq, "일상과 회의 기록", "안경 음성을 인식하고 필요한 정보를 찾아보기")
                    SetupRow(Icons.Outlined.AutoAwesome, "언제든 AI에게 질문", "연결한 AI 서비스 또는 컴퓨터에 작업 요청")
                    SetupRow(Icons.Outlined.Tune, "직접 제어", "표시와 음성 수신은 언제든 일시정지 가능")
                    RecommendedSettingsCard(model)
                }
                1 -> {
                    OpticalPanel {
                        GlassesArtwork(
                            Modifier.fillMaxWidth().height(110.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (model.state.available && model.state.glasses.link.phase == LinkPhase.FULL && !model.state.glasses.disconnecting) "통신 연결됨"
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
                    OpticalPanel {
                        Text(if (model.state.conversation.modelReady) "음성 인식 준비됨" else "음성 인식 설정 필요", style = MaterialTheme.typography.titleMedium)
                        Note("한국어·다국어 음성 모델이 내장되어 별도 다운로드가 필요하지 않습니다. 처음 사용할 때 앱이 준비합니다.")
                        if (!model.state.conversation.modelReady)
                            ToolOutlinedButton(onClick = { configure("voice-settings") }, modifier = Modifier.fillMaxWidth()) { Text("음성 상태 확인") }
                        Note("원본 음성 보존 및 AI 전송 범위는 음성·기억 설정에서 확인할 수 있습니다.")
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
                    Note("로컬 Agent의 의미 전략과 모델은 DSH Go에서 설정합니다. 기록 요약 모델은 '음성 및 요약' 설정에서 따로 설정할 수 있습니다. 휴대폰 음성 입력은 앱 내장 음성 인식 모델을 사용합니다.")
                }
                4 -> {
                    OpticalPanel {
                        CompletionRow(
                            "안경 연결",
                            model.state.glasses.link.phase == LinkPhase.FULL,
                            "연결됨",
                            "나중에 연결",
                        )
                        CompletionRow("음성 인식", model.speechConfigured, "선택한 모델 확보 또는 외부 설정됨", "음성 인식 설정 확인")
                        CompletionRow("기록 엔진", model.state.conversation.modelReady, "초기화 완료", "초기화 상태 확인")
                        CompletionRow(
                            "로컬 Agent",
                            model.state.agentServer.enabled && model.state.agentServer.pollRecent,
                            "최근 요청 수신 확인 · 모델 응답은 별도",
                            if (model.state.agentServer.enabled) "연결 켜짐 · 요청 수신 대기" else "선택 사항, 나중에 설정",
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
        if (AppUpdate.supported) SettingsGroup {
            SettingsRow("앱 업데이트", model.updateCheck.summary, Icons.Outlined.Settings) { open("app-update") }
        }
        SectionLabel("AI 연결")
        SettingsGroup {
            SettingsRow(
                "모델 선택",
                model.aiStatus,
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
            SettingsRow("AI 연결 · DSH Go", model.state.agentServer.receiverStatus + " · 연결 및 동기화", Icons.Outlined.Hub) { open("agent-api") }
        }
        SectionLabel("음성·기억 설정")
        SettingsGroup {
            SettingsRow("자동 기능 관리", "호출 · 힌트 · 기억 · 알림의 실제 상태와 허용 설정", Icons.Outlined.Tune) { open("feature-controls") }
            SettingsRow("음성·기억 설정", "내장 음성 모델 · 언어 · 기억 및 요약", Icons.Outlined.Mic) { open("voice-settings") }
            SettingsRow("About You · 개인화 정보", "승인한 프로필·선호·관심사 관리", Icons.Outlined.People) { open("about-you") }
            SettingsRow("한국어 호출어", "내 호출어 · 듣기 시작 및 중지", Icons.Outlined.Mic) { open("korean-wake") }
            SettingsRow("목소리 및 인물", "본인 등록, 연락처 및 목소리 프로필", Icons.Outlined.People) { open("voice-profiles") }
        }
        SectionLabel("기록 및 데이터")
        SettingsGroup {
            SettingsRow("기존 기록 가져오기", "다른 설치본에서 전사·요약·음성 이전", Icons.Outlined.FolderOpen) { open("record-migration") }
        }
        SectionLabel("안경 설정")
        SettingsGroup {
            SettingsRow("안경 설정", "밝기, 화면 위치 및 깨우기", Icons.Outlined.Settings) { open("device-settings") }
            SettingsRow("홈 화면 및 콘텐츠", "위젯, 할 일, 일정 및 날씨", Icons.Outlined.Dashboard) { open("content") }
            SettingsRow("프롬프터 원고함", "로컬 원고, 안경 재생 및 제어", Icons.Outlined.Description) { open("teleprompter") }

            SettingsRow("기기 및 진단", "연결 로그, 테스트 알림 및 오디오 복구", Icons.Outlined.Tune) { open("diagnostics") }
        }
        SectionLabel("휴대폰 권한")
        SettingsGroup {
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
        }
        SectionLabel("화면 모드")
        OpticalPanel {
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
        SectionLabel("앱 정보")
        SettingsGroup {
            SettingsRow("사용 안내 다시 보기", "안경 연결, 내비게이션 데모 및 어시스턴트 설정", Icons.Outlined.Explore, replay)
        }
        Note(
            "OpenRayneo ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.GIT_COMMIT} (${BuildConfig.VERSION_CODE})\n기록 오디오는 휴대폰에 암호화 저장되며 전사와 화자 분석이 끝난 뒤 전달됩니다. 휴대폰 음성 입력은 앱 내장 모델을 사용합니다.",
            Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, icon: ImageVector, click: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = click),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
internal fun ConfigurationScreen(model: AppModel, mobile: Boolean, importConfig: () -> Unit) {
    if (mobile) { ModelSelectionScreen(model); return }

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
        OpticalPanel {
            Text("컴퓨터 연결", style = MaterialTheme.typography.headlineSmall)
            Note(when {
                !model.state.available -> "서비스 상태 미확인"
                model.state.computerConnected -> "컴퓨터 연결 확인"
                model.config.optString("codexUrl").isNotBlank() && model.config.optString("codexToken").isNotBlank() -> "설정 저장됨 · 연결 미확인"
                else -> "연결 설정 필요"
            })
            Note(if (url != model.config.optString(urlKey) || name != model.config.optString(nameKey) || secret != model.config.optString(secretKey)) "저장되지 않은 입력이 있습니다." else "저장된 설정")
        }
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
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DiagnosticExportPanel(model)
        OpticalPanel {
            Text("현재 연결 상태", style = MaterialTheme.typography.titleLarge)
            Text(deviceConnectionStatus(s).label, style = MaterialTheme.typography.titleMedium)
            Note(if (s.available) "통신: " + s.glasses.link.detail else "서비스 상태 미확인")
            if (s.available) {
                Note("표시 세션: " + s.glasses.displayDescription)
                Note("음성: " + s.glasses.audio.detail)
            }
            Note("통신·기기 응답·실제 안경 표시와 음성 수신은 별개입니다.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("최근 연결 종료 및 복구", style = MaterialTheme.typography.titleMedium)
            OfficialAppConnectionControls()
            val health = if (s.available) runCatching { model.service().connectionHealth }.getOrDefault("진단 준비 중") else "진단 준비 중"
            Note(health)
            if (s.available) {
                Note(model.service().connection.linkEvidence)
                Note(model.service().capabilityStatus)
                Note(model.service().peerCapabilityStatus)
                Note(model.service().nativeMenuDiagnostics)
                TextButton(enabled = s.glasses.link.phase == LinkPhase.FULL,
                    onClick = { model.service().probeNativeNavigation() }) {
                    Text("순정 내비 지원 조회 (실험)")
                }
            }
            Note("최근 연결 종료와 복구 기록입니다. 이전 서비스 종료 기록 누락은 앱 업데이트, 재부팅, 강제 종료 등으로 정상 종료 기록이 남지 않았음을 뜻합니다. 음성·대화·기기 주소는 저장하지 않습니다.")
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("연결 진단", if (s.available)
                        listOf(health, model.service().connection.linkEvidence, model.service().capabilityStatus,
                            model.service().peerCapabilityStatus, model.service().nativeMenuDiagnostics,
                            model.service().hostVisibilityStatus, model.service().nativeWakeStatus).joinToString("\n") else health))
                model.notice = "연결 진단 복사됨"
            }) { Text("연결 진단만 복사") }
        }
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
            Text("기능 시험 및 복구", style = MaterialTheme.typography.titleMedium)
            Note("시험 명령을 보내도 실제 안경 출력은 직접 확인해야 합니다.")
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().connection.sendTestNotification() } },
                enabled = s.available && ready && !s.glasses.disconnecting,
            ) {
                Text("테스트 알림 보내기")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().retryAudio() } },
                enabled =
                    s.available && !s.glasses.disconnecting && s.glasses.session?.phase in
                        setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY),
            ) {
                Text("오디오 디코딩 재시작 (페이지 유지)")
            }
            ConnectionReleaseControls(model)
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
        TextButton(onClick = { showLogs = !showLogs }) {
            Text(if (showLogs) "연결 로그 접기" else "연결 로그 보기 · ${s.logs.size}개")
        }
        if (showLogs) OpticalPanel { Note(s.logs.joinToString("\n").ifBlank { "연결 로그 없음" }) }
    }
}

// User explicitly previews the exact export. No automatic transfer, storage
// permission, microphone recording, extra endpoint or new AI history retention.
@Composable
private fun DiagnosticExportPanel(model: AppModel) {
    val context = LocalContext.current
    var includeDetails by remember { mutableStateOf(false) }
    var includeAi by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val report = pendingFile
        pendingFile = null
        if (uri != null && report != null) model.attempt {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(report) }
            model.notice = "진단 파일 저장 완료 · ChatGPT에 첨부할 수 있습니다"
        }
    }
    ContentCard {
        Text("전체 기능 진단 내보내기", style = MaterialTheme.typography.titleMedium)
        Note("문제를 재현한 뒤 보고서를 만들면 연결·순정 내비·라이프로그·실시간 자막·실시간 답변의 단계와 최근 300개 진단 이력이 포함됩니다. 기본 보고서는 음성 원문, 위치, 인증 정보 없이 생성됩니다.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeDetails, { includeDetails = it })
            Text("현재 오류 메시지와 기존 앱 로그 포함")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeAi, { includeAi = it })
            Text("기존 AI 지시·답변 원문 포함")
        }
        Note("선택 항목에는 개인 정보가 있을 수 있으므로 미리보기를 확인한 뒤 직접 저장하거나 복사하세요. 기록하지 않도록 설정한 질문과 원본 녹음은 새로 저장하지 않습니다.")
        ToolButton(enabled = model.state.available, onClick = {
            model.attempt { preview = model.diagnosticReport(includeDetails, includeAi) }
        }) { Text("진단 보고서 미리보기") }
        TextButton(enabled = model.state.available, onClick = { confirmClear = true }) { Text("진단 이력만 초기화") }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("진단 이력 초기화") },
        text = { Text("기능 진단 이벤트만 삭제합니다. AI 대화와 라이프로그 기록은 변경하지 않습니다.") },
        confirmButton = { TextButton(onClick = { model.attempt { model.service().clearDiagnosticTrace() }; confirmClear = false }) { Text("초기화") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
    preview?.let { report ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text("전송 전 보고서 확인") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                pendingFile = report
                launcher.launch("OpenRayneo-diagnostics-${System.currentTimeMillis()}.txt")
            }) { Text("파일 저장") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenRayneo diagnostics", report))
                        model.notice = "진단 보고서 복사됨"; preview = null
                    }) { Text("복사") }
                    TextButton(onClick = { preview = null }) { Text("닫기") }
                }
            })
    }
}
