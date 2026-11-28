// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionApp(model: AppModel, actions: UiActions, phoneInput: String? = null, statusOpenRequest: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var routeStack by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val pages = rememberSaveableStateHolder()
    val route = routeStack.lastOrNull().orEmpty()
    fun changeRoutes(next: List<String>) {
        // Preserve existing rememberSaveable scroll/selection state only while
        // a detail remains on the back stack. Popped routes start fresh.
        CompanionRoutePolicy.removed(routeStack, next).forEach { pages.removeState("route:$it") }
        routeStack = ArrayList(next)
    }
    fun openRoute(target: String) {
        if (CompanionRoutePolicy.title(target, AppUpdate.supported) == null) {
            model.notice = "지원되지 않는 화면입니다."
            return
        }
        changeRoutes(CompanionRoutePolicy.open(routeStack, target, AppUpdate.supported))
    }
    fun backRoute() { changeRoutes(CompanionRoutePolicy.back(routeStack)) }
    fun closeRoutes() { changeRoutes(emptyList()) }
    var pendingNavigation by remember { mutableStateOf<String?>(null) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(statusOpenRequest) {
        if (statusOpenRequest > 0) {
            devices = false
            sources = false
            pendingNavigation = null
            openRoute("system-status")
        }
    }
    LaunchedEffect(model.externalHudOpen) {
        if (AppUpdate.supported && model.externalHudOpen) {
            model.externalHudOpen = false
            devices = false
            sources = false
            pendingNavigation = null
            openRoute("external-hud")
        }
    }
    val snackbars = remember { SnackbarHostState() }
    // ViewModel-owned drafts survive configuration changes, not process death.
    val manuscript = model.teleprompterDraft
    val contentDrafts = model.contentEditorDrafts
    val s = model.state
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(
        enabled =
            !devices &&
                !sources &&
                (route.isNotEmpty() || model.onboarding && model.onboardingStep > 0 || tab != 0)
    ) {
        if (route.isNotEmpty())
            backRoute()
        else if (model.onboarding) model.step(model.onboardingStep - 1) else tab = 0
    }
    // Clearing the notice must not cancel the coroutine displaying its snackbar.
    LaunchedEffect(Unit) {
        snapshotFlow { model.notice }.filterNotNull().collect { message ->
            model.notice = null
            snackbars.showSnackbar(message)
        }
    }
    Scaffold(
        topBar = {
            if (!model.onboarding || route.isNotEmpty())
                Column {
                OpticalFrameHeader(
                    title = CompanionRoutePolicy.title(route, AppUpdate.supported)
                        ?: listOf("홈", "AI", "기억", "도구", "기기")[tab.coerceIn(0, 4)],
                    isDetail = route.isNotEmpty(), compact = keyboardVisible,
                    back = { backRoute() }, settings = { openRoute("settings") }
                )
                if (route != "system-status") SystemStatusStrip(model, phoneInput, compact = keyboardVisible) { openRoute("system-status") }
                }
        },
        bottomBar = {
            if (route.isEmpty() && !model.onboarding && !keyboardVisible)
                OpticalFrameNavigation(tab, s.approvals.size + s.agentReviews.size) { tab = it }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (route.isNotEmpty()) pages.SaveableStateProvider("route:$route") {
                when (route) {
                "settings" ->
                    SettingsScreen(
                        model,
                        actions,
                        { openRoute(it) },
                        {
                            model.replaySetup()
                            closeRoutes()
                        },
                    )
                "system-status" -> SystemStatusScreen(model, phoneInput,
                    { tab = it; closeRoutes() }, { openRoute(it) }, { devices = true })
                "feature-controls" -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.recommendedPending) item { RecommendedSettingsCard(model) }
                    item { HomeFeatureDashboard(model, { tab = it; closeRoutes() }, { openRoute(it) }, { devices = true }) }
                }
                "model",
                "computer" -> ConfigurationScreen(model, route == "model", actions.importConfig)
                "app-update" -> if (AppUpdate.supported) AppUpdateScreen(model) else Text("이 앱은 자체 업데이트 대상이 아닙니다.")
                "external-hud" -> if (AppUpdate.supported) ExternalHudScreen(model, phoneInput != null)
                    else Text("검증 앱에서만 사용할 수 있습니다.")
                "local-clock" -> LocalClockScreen(model)
                "about-you" -> PersonalizationScreen()
                "diagnostics" -> DiagnosticsScreen(model, actions)
                "voice-settings" -> VoiceSettingsScreen(model, actions)
                "voice-profiles" -> VoiceProfilesScreen(model)
                "device-settings" -> GlassesSettingsScreen(model)
                "content" -> CompanionContentScreen(model, contentDrafts)
                "teleprompter" -> TeleprompterScreen(model, manuscript)
                "agent-api" -> AgentServerScreen(model, actions.bluetoothPermission)
                "navigation" -> NavigationScreen(model, actions, { sources = true }, { key ->
                    if (s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending) pendingNavigation = key
                    else model.attempt { model.service().followNavigation(key) }
                }, { devices = true })
                "translation" -> TranslationToolScreen(model)
                "media-captions" -> MediaCaptionScreen(model, actions.mediaCaption, { openRoute("voice-settings") }, { devices = true })
                "captions" -> CaptionToolScreen(model, { openRoute("voice-settings") }, { openRoute("media-captions") })
                "korean-wake" -> KoreanWakeScreen(model, { openRoute("voice-settings") }, { openRoute("device-settings") })
                "korean-glyphs" -> KoreanGlyphScreen(model)
                "phone-notifications" -> PhoneNotificationScreen(model, actions)
                "record-migration" -> RecordMigrationScreen(model)
                "display-controls" -> GlassesScreen(model, { devices = true }, { tab = it; closeRoutes() },
                    { openRoute("diagnostics") }, { openRoute(it) })
                    else -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("지원되지 않는 화면입니다.", style = MaterialTheme.typography.titleMedium)
                        ToolButton(onClick = { closeRoutes() }) { Text("기본 화면으로 돌아가기") }
                    }
                }
            } else if (model.onboarding) {
                OnboardingScreen(model, actions, { devices = true }, { openRoute(it) })
            } else {
                pages.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> HomeScreen(model, { tab = it }, { openRoute(it) }, { devices = true })
                        1 -> AssistantScreen(model, actions, keyboardVisible) { openRoute(it) }
                        2 -> ConversationScreen(model, actions, { openRoute(it) })
                        3 -> ToolsScreen(model, { openRoute(it) }, { tab = it })
                        else -> DeviceOverview(model, { devices = true }, { openRoute(it) })
                    }
                }
            }
        }
    }
    if (devices)
        ModalBottomSheet(onDismissRequest = { devices = false }) { DevicePicker(model, actions) }
    if (sources)
        ModalBottomSheet(onDismissRequest = { sources = false }) {
            var sourceTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    sourceTime = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("네이버지도 안내 선택", style = MaterialTheme.typography.headlineSmall)
                Note("회전 방향과 거리가 포함된 알림을 선택하세요. '내비게이션 중' 메시지만으로는 방향을 알 수 없습니다.", Modifier.padding(vertical = 12.dp))
                if (s.navigationEntries.isEmpty()) Note("아직 길 안내 알림을 받지 못했습니다")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.navigationEntries, key = { it.key }) { entry ->
                        val current = entry.safeInstruction != null && entry.freshAt(sourceTime)
                        OutlinedCard(
                            enabled = current && s.notificationConnected,
                            onClick = {
                                if (s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending) {
                                    pendingNavigation = entry.key
                                    sources = false
                                } else model.attempt {
                                    model.service().followNavigation(entry.key)
                                    sources = false
                                }
                            }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    entry.sourceName + " · " + entry.title.ifBlank { "길 안내" },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Note(entry.texts.filter { it != entry.title }.joinToString("\n"))
                                Text(
                                    if (
                                        entry.key == s.glasses.navigation.key &&
                                            s.glasses.navigation.enabled
                                    )
                                        "따라가는 중"
                                    else if (!current) "최신 갱신 대기" else "이 알림 따라가기",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    pendingNavigation?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingNavigation = null },
            title = { Text("기록을 종료하고 길 안내를 시작할까요?") },
            text = { Text("현재 기록과 자동 재개를 중단합니다. 기록을 마친 뒤 같은 알림의 최신 방향·거리가 확인될 때만 30초 이내에 전환합니다. 전환에 실패해도 종료한 기록은 자동 재시작하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                pendingNavigation = null
                model.attempt { model.service().requestNavigationSwitch(key) }
            }) { Text("기록 종료 및 전환") } },
            dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("취소") } }
        )
    }
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            title = { Text("안내") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("확인") } },
        )
    }
}

@Composable
private fun GlassesScreen(
    model: AppModel,
    connect: () -> Unit,
    navigate: (Int) -> Unit,
    diagnostics: () -> Unit,
    configure: (String) -> Unit,
) {
    val state = model.state.glasses
    val linked = state.link.phase == LinkPhase.FULL
    var handoff by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy =
        state.link.phase in
            setOf(LinkPhase.CONNECTING, LinkPhase.SCANNING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OpticalPanel {
                Text(model.deviceName, style = MaterialTheme.typography.headlineSmall)
                Note(if (linked) "BLE·SPP 연결 · " + model.state.deviceSettings.readback.label else state.link.detail)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolOutlinedButton(
                        onClick =
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) diagnostics
                            else connect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) "관리"
                            else if (busy) "연결 진행 상황" else "연결"
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (linked || state.link.phase == LinkPhase.BLE_ONLY || busy) {
                    ToolOutlinedButton(
                        onClick = { model.attempt { model.service().disconnect() } },
                        enabled = !state.disconnecting && state.link.phase != LinkPhase.CLOSING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.disconnecting) "연결 해제 중…" else "연결 해제")
                    }
                    Note("연결을 해제하면 표시와 음성 수신이 끝나고 자동 재연결이 중지되며 페어링 기록은 유지됩니다. 이후 다른 기기로 연결을 전환할 수 있습니다.")
                }
            }
        }
        item {
            OpticalPanel {
                SectionLabel("현재 화면 및 음성 제어")
                Note(if (linked) state.displayDescription else "현재 안경 표시 상태 미확인 · 통신 연결 후 확인하세요.")
                if (linked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolOutlinedButton(onClick = { configure("navigation") }) { Text("길 안내") }
                        ToolOutlinedButton(onClick = { navigate(0) }) { Text("홈으로") }
                    }
                    if (model.state.wakeActive || model.state.wakePending)
                        TextButton(onClick = { model.attempt { model.service().cancelKoreanWake() } }) { Text("한국어 호출 중지") }
                    if (model.state.captionActive)
                        TextButton(onClick = { model.attempt { model.service().stopCaption() } }) { Text("실시간 자막 종료") }
                    if (!model.state.translationActive)
                        ToolOutlinedButton(
                            onClick = { configure("translation") }
                        ) {
                            Text("실시간 번역 (한국어 ↔ 영어)")
                        }
                    if (model.state.mediaCaption.busy) {
                        Note(model.state.mediaCaption.detail)
                        TextButton(onClick = { model.attempt { model.service().stopMediaCaption() } }) { Text("미디어 자막 종료") }
                    } else if (model.state.translationActive && !model.state.captionActive && !model.state.wakeActive) {
                        Note("실시간 번역 (한국어 ↔ 영어): ${model.state.translationStatus}")
                        TextButton(onClick = { model.attempt { model.service().stopTranslation() } }) {
                            Text("번역 종료")
                        }
                    } else if (model.state.translationStatus != "실시간 번역 꺼짐") Note(model.state.translationStatus)
                    if (!model.state.mediaCaption.busy && state.session?.mode == PageMode.PROMPT && state.session.phase == PagePhase.READY)
                        TextButton(onClick = { model.attempt { model.service().clearDisplay() } }) {
                            Text("화면 지우기 (음성 수신 유지)")
                        }
                    if (state.intent is DisplayIntent.Answer)
                        TextButton(
                            onClick = { model.attempt { model.service().dismissAnswer() } }
                        ) {
                            Text("안경 답변 종료")
                        }
                    if (state.session != null)
                        TextButton(onClick = { model.attempt { model.service().pausePrompt() } }) {
                            Text("표시 및 음성 수신 일시정지")
                        }
                }
            }
        }
        item {
            ContentCard {
                Text("공식 앱 / OpenRayneo 전환", style = MaterialTheme.typography.titleMedium)
                Note("계정 바인딩, 블루투스 페어링, 앱 기록을 삭제하지 마세요. 한 번에 한 앱만 안경에 연결합니다.")
                ToolOutlinedButton(
                    onClick = {
                        handoff = true
                        if (state.link.phase != LinkPhase.DISCONNECTED || state.disconnecting)
                            model.attempt { model.service().disconnect() }
                    },
                    enabled = !state.disconnecting && state.link.phase != LinkPhase.CLOSING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("공식 앱으로 전환 준비") }
                if (handoff) {
                    if (state.link.phase == LinkPhase.DISCONNECTED && !state.disconnecting) {
                        Note("OpenRayneo 연결이 종료되었습니다. 공식 앱의 근처 기기 권한을 허용한 뒤 공식 앱을 직접 열어 연결하세요.")
                        ToolOutlinedButton(onClick = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))
                        }) { Text("휴대폰 앱 설정 열기") }
                    } else Note("연결 종료를 확인한 후 공식 앱을 여세요.")
                }
                ToolOutlinedButton(
                    onClick = {
                        handoff = false
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))
                    },
                    enabled = state.link.phase == LinkPhase.DISCONNECTED && !state.disconnecting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("OpenRayneo로 돌아오기 준비") }
                Note("돌아올 때는 공식 앱(기존 검증 앱 포함)의 시스템 앱 정보에서 강제 종료하고, 필요하면 근처 기기 권한을 일시적으로 끄세요. 이후 이 앱으로 돌아와 연결 버튼을 누르세요. 공식 앱 내부의 기기 삭제·바인딩 해제는 하지 마세요.")
            }
        }
        item { SectionLabel("안경 화면") }
        item { GlassesOutputPreview(state) }
        item {
            OpticalPanel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("안경 오디오", style = MaterialTheme.typography.titleSmall)
                        Note(state.audio.detail)
                    }
                }
                if (state.audio.phase == AudioPhase.RECEIVING) {
                    val levels = model.state.audioLevels
                    val channels =
                        if (levels.channels == 1) listOf("모노" to levels.left)
                        else listOf("채널 1" to levels.left, "채널 2" to levels.right)
                    channels.forEach { (label, rms) ->
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = {
                                ((20 * kotlin.math.log10(rms.coerceAtLeast(.00001)) + 60) / 60)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Note("음량은 휴대폰에서만 계산하며 오디오를 저장하거나 업로드하지 않습니다.")
            }
        }


    }
}

@Composable
internal fun FeatureTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    click: () -> Unit,
) {
    Card(
        onClick = click,
        modifier = modifier.heightIn(min = 124.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
        }
    }
}

/** Compact action with a readable label, purpose and navigation affordance. */
@Composable
internal fun FeatureLink(title: String, subtitle: String, icon: ImageVector, click: () -> Unit) {
    OutlinedCard(onClick = click, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
private fun DevicePicker(model: AppModel, actions: UiActions) {
    val s = model.state
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val connecting =
        s.glasses.link.phase in setOf(LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OpticalPanel {
                Text(if (ready) "현재 안경 연결" else "안경 연결하기", style = MaterialTheme.typography.headlineSmall)
                Text(deviceConnectionStatus(s).label, style = MaterialTheme.typography.titleSmall)
        Note("안경을 깨워 휴대폰 가까이 두세요. 기존 페어링은 유지합니다.")
        if (s.glasses.link.lastFailure != null) Note("다른 앱이 연결을 점유한 경우에만 해당 앱의 안경 연결을 종료하고 다시 시도하세요. 페어링·계정·앱 데이터를 삭제하거나 안경을 초기화하지 마세요.")
        Text(s.glasses.link.detail, color = MaterialTheme.colorScheme.primary)
        s.glasses.link.lastFailure?.let { Note("최근 연결 원인: $it") }
        if (s.glasses.link.reconnectWaiting) Note("현재 안경은 미연결 상태입니다. 자동 재연결을 기다리거나 아래에서 다시 스캔하세요.")
                if (ready || connecting)
                    ToolOutlinedButton(onClick = { model.attempt { model.service().disconnect() } }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ready) "안경 연결 해제" else "연결 취소")
                    }
            }
        }
        item {
        if (!model.bluetoothAllowed) {
            ToolButton(onClick = actions.scan, modifier = Modifier.fillMaxWidth()) {
                Text("근처 기기 허용 후 스캔")
            }
            TextButton(onClick = actions.appSettings) { Text("앱 권한 설정으로 이동") }
        } else {
            ToolButton(
                onClick = actions.scan,
                enabled = !ready && !connecting && s.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null)
                Spacer(Modifier.width(8.dp))
                Text("안경 스캔")
            }
        }
        if (connecting || s.glasses.link.phase == LinkPhase.SCANNING)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!ready && !connecting) {
            item { SectionLabel("찾은 안경") }
            items(s.devices, key = { it.bluetooth.address }) { device ->
                OutlinedCard(
                    onClick = { model.connect(device, false) },
                    enabled = s.available && model.bluetoothAllowed && !ready && !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text("탭하여 연결") },
                        leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        if (s.devices.isEmpty() && model.bluetoothAllowed && !connecting && !ready)
            item { Note("안경이 보이지 않으면 안경을 깨운 뒤 다시 스캔하세요.") }
    }
}

@Composable
private fun NavigationScreen(
    model: AppModel,
    actions: UiActions,
    sources: () -> Unit,
    follow: (String) -> Unit,
    connect: () -> Unit,
) {
    val s = model.state
    val nav = s.glasses.navigation
    val entry = s.navigationEntries.firstOrNull { it.key == nav.key }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val instruction = entry?.safeInstruction?.takeIf { nav.enabled && s.notificationConnected && entry.freshAt(time) }
    val candidates = s.navigationEntries.filter { it.safeInstruction != null && it.freshAt(time) }
    LaunchedEffect(entry?.updatedAt, nav.enabled, s.navigationEntries.isNotEmpty()) {
        if (nav.enabled || s.navigationEntries.isNotEmpty()) while (true) {
            time = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OpticalPanel {
                Text("네이버지도 실시간 안내", style = MaterialTheme.typography.titleMedium)
                if (instruction != null) {
                    Text(
                        "${instruction.direction} · ${instruction.meters} m",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Note(entry!!.title)
                } else Note(s.navigationStatus)
                val frame = nav.frame
                if (
                    frame != null &&
                        (s.glasses.session?.mode != PageMode.NAVIGATION ||
                            s.glasses.session?.writtenContent?.text != frame)
                ) {
                    GridPreview(frame, "내비게이션 레이아웃 전송 대기")
                }
                Note("네이버지도에서 확인된 방향과 거리만 안경으로 보냅니다.")
            }
        }
        item {
            ContentCard {
                Text(
                    if (nav.enabled) "네이버지도 안내 연동" else "네이버지도 연결",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (nav.enabled) {
                    ToolOutlinedButton(
                        onClick = { model.attempt { model.service().stopNavigation() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("안경 추종 중지")
                    }
                    Note("안경 안내만 종료하며 네이버지도의 길 안내는 계속됩니다. 기본 호출 후 '안경 길 안내 종료해 줘'라고 요청할 수 있습니다. 음성 전환은 안경 펌웨어에서 실제 검증이 필요합니다.")
                }
                Note(s.notificationReadStatus)
                if (
                    s.notificationReadPhase == NotificationReadPhase.FAILED &&
                        android.os.Build.MANUFACTURER.equals("xiaomi", true)
                ) {
                    Note("Xiaomi / HyperOS의 백그라운드 자동 시작 제한이 리스너 연결을 막을 수 있습니다. OpenRayneo의 '백그라운드 자동 시작'을 허용한 뒤 알림 읽기를 복구하세요.")
                    TextButton(onClick = actions.appSettings) { Text("앱 설정 확인") }
                }
                Note(s.navigationStatus)
                if (s.navigationSwitchStatus.isNotBlank()) Note(s.navigationSwitchStatus)
                if (s.navigationSwitchPending) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    ToolOutlinedButton(onClick = { model.attempt { model.service().cancelNavigationSwitch() } }) { Text("길 안내 전환 취소") }
                }
                if (entry != null && nav.enabled) {
                    val age = ((time - entry.updatedAt).coerceAtLeast(0) / 1000)
                    Note("최근 알림 수신: ${if (age < 60) "${age}초 전" else "${age / 60}분 전"} · 휴대폰 지도 앱의 실시간 안내를 기준으로 하세요")
                }
                if (!model.notificationAllowed)
                    ToolButton(
                        onClick = actions.notificationAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("알림 접근 허용")
                    }
                else if (!nav.enabled && !s.navigationSwitchPending)
                    Column {
                        Note("네이버지도에서 도보 또는 내비게이션 안내를 시작한 뒤 돌아오세요.")
                        if (NavigationFeed.PACKAGE !in model.installedMaps) Note("네이버지도 앱 설치가 필요합니다.")
                        else ToolButton(onClick = actions.openNaverMap, modifier = Modifier.fillMaxWidth()) { Text("네이버지도 열기") }
                        if (candidates.isNotEmpty() && s.notificationConnected) {
                            if (s.glasses.link.phase != LinkPhase.FULL || s.glasses.disconnecting)
                                ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text("안경 먼저 연결") }
                            else ToolButton(onClick = { if (candidates.size == 1) follow(candidates.single().key) else sources() },
                                modifier = Modifier.fillMaxWidth()) { Text("안경 길 안내 시작") }
                        } else Note("최신 방향·거리 안내 수신 대기")
                    }
                if (nav.enabled) {
                    if (s.glasses.link.phase != LinkPhase.FULL)
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text("안경 연결")
                        }
                    else {
                        Note(s.glasses.displayDescription)
                        if (
                            s.glasses.intent != DisplayIntent.Navigation ||
                                s.glasses.ownership != DisplayOwnership.APP ||
                                s.glasses.error != null
                        )
                            ToolButton(
                                onClick = { model.attempt { model.service().reopenNavigation() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("안경 내비게이션 재개")
                            }
                    }

                }
            }
        }
        item { SectionLabel("안경 화면") }
        item { GlassesOutputPreview(s.glasses) }
        if (candidates.size > 1 || nav.enabled && candidates.any { it.key != nav.key } && instruction == null) item {
            OutlinedCard(onClick = sources) {
                ListItem(
                    headlineContent = { Text("네이버지도 안내 선택") },
                    supportingContent = {
                        Text(
                            if (s.navigationEntries.isEmpty()) "길 안내 알림 대기 중"
                            else "${candidates.size}개 최신 안내 · 탭하여 선택"
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.NotificationsNone, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            ContentCard {
                Text("네이버지도 수신 상태", style = MaterialTheme.typography.titleMedium)
                Note("알림 분류·방향/거리만 확인하며, 해석할 수 없는 알림의 원문은 저장하지 않습니다.")
                NavigationFeed.sources.filterKeys { it in model.installedMaps }.forEach { (pkg, label) ->
                    val evidence = s.navigationEvidence.firstOrNull { it.packageName == pkg }
                    val recent = candidates.any { it.packageName == pkg }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Note(when {
                        !model.notificationAllowed -> "알림 접근 권한 필요"
                        !s.notificationConnected -> "알림 리스너 연결 대기"
                        recent -> "최신 방향·거리 인식 · 안경 안내 시작 가능"
                        (evidence?.recognized ?: 0) > 0 -> "방향·거리 인식했으나 최근 갱신 없음"
                        (evidence?.unrecognized ?: 0) > 0 -> "길 안내 알림 수신 · 방향/거리 해석 불가"
                        (evidence?.observed ?: 0) > 0 -> "일반 알림만 확인 · 시스템 길 안내 알림 없음"
                        else -> "현재 수신된 알림 없음 · 지도 앱에서 안내 시작 확인"
                    })
                }
                ToolOutlinedButton(onClick = { model.attempt { NavigationNotifications.refresh(model.getApplication(), foreground = true) } }, enabled = model.notificationAllowed) { Text("현재 알림 다시 확인") }
            }
        }
        item {
            ContentCard {
                Text("안경으로 지도 자동 실행 (선택)", style = MaterialTheme.typography.titleMedium)
                val address = runCatching { model.service().authenticatedNavigationDevice }.getOrNull()
                val associated = NaverCompanionAccess.allowed(model.getApplication(), address)
                Note(if (associated) "현재 연결된 안경의 시스템 부속 기기 등록 확인됨" else "현재 안경의 자동 실행 등록 없음")
                Note("한 번 시스템 등록을 승인하면 안경의 명시적 길찾기 요청으로 네이버지도 백그라운드 실행을 시도합니다. 잠금 화면을 해제하거나 우회하지 않습니다. 등록은 기존 블루투스 연결과 별개입니다.")
                ToolButton(onClick = actions.registerNaverCompanion, enabled = address != null && !associated,
                    modifier = Modifier.fillMaxWidth()) { Text("현재 안경 시스템 등록 및 사용") }
                ToolOutlinedButton(onClick = actions.revokeNaverCompanion,
                    modifier = Modifier.fillMaxWidth()) { Text("자동 실행 사용 중지 및 등록 해제") }
                Note("실제 안내는 네이버지도에서 확인된 신규 알림만 안경에 표시합니다. 실행이 제한된 경우 휴대폰 알림을 사용합니다.")
            }
        }
        item { NaverPlaceLookupSettings(model) }
        item {
            Note("네이버지도의 시스템 길 안내 알림에서 명확한 방향·거리가 확인될 때만 안경에 표시합니다. 실제 알림 형식은 아직 실기기 검증 전이며, 안내가 없거나 오래되면 휴대폰 네이버지도를 확인하세요.")
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
            Note("알림 권한과 추종 선택을 유지한 채 리스너를 다시 연결하고 현재 알림을 읽습니다.")
            Note("위의 안경 화면은 전송 결과에 따라서만 갱신되며 지도 앱 알림은 따로 표시됩니다. 종료되거나 연결이 끊기면 이전 화면을 현재 화면으로 간주하지 않습니다.")
        }
    }
}

@Composable
private fun NaverPlaceLookupSettings(model: AppModel) {
    val context = model.getApplication<android.app.Application>()
    var saved by remember { mutableStateOf(runCatching { NaverPlaceLookup.read(context) }.getOrDefault(NaverPlaceLookup.Config())) }
    var id by remember { mutableStateOf(saved.id) }
    var secret by remember { mutableStateOf(saved.secret) }
    var mode by remember { mutableStateOf(saved.defaultMode) }
    ContentCard {
        Text("음성 길찾기 목적지 자동 확인 (선택)", style = MaterialTheme.typography.titleMedium)
        Note(if (saved.enabled) "지역 검색 API 설정됨 · 실제 인증 및 응답은 호출 시 확인합니다." else "기본값: 별도 API 없이 네이버지도 검색을 사용합니다.")
        Note("NAVER API HUB 인증 정보를 저장하면 명시적 길찾기 요청의 장소 이름만 네이버 지역 검색에 전달합니다. 서비스는 한시적 무료이며 향후 유료화될 수 있습니다. 직접 요금 조건을 확인하고 사용하세요. 현재 위치·대화 기록은 보내지 않습니다.")
        OutlinedTextField(id, { id = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Client ID") }, singleLine = true)
        OutlinedTextField(secret, { secret = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Client Secret") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("이동 수단을 말하지 않았을 때", fontWeight = FontWeight.Medium)
        for ((option, label) in listOf(NaverVoiceCommand.Mode.UNSPECIFIED to "지도 검색 화면", NaverVoiceCommand.Mode.WALK to "도보",
            NaverVoiceCommand.Mode.CAR to "자동차", NaverVoiceCommand.Mode.PUBLIC to "대중교통")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { mode = option })
                Text(label, modifier = Modifier.clickable { mode = option })
            }
        }
        ToolButton(onClick = { model.attempt {
            require(id.isNotBlank() && secret.isNotBlank()) { "인증 정보를 모두 입력하세요." }
            val next = NaverPlaceLookup.Config(id.trim(), secret.trim(), mode)
            NaverPlaceLookup.save(context, next)
            runCatching { model.service().cancelVoiceNavigationRequest() }
            saved = next
        } }, modifier = Modifier.fillMaxWidth()) { Text("인증 정보 저장 및 사용") }
        ToolOutlinedButton(onClick = { model.attempt {
            NaverPlaceLookup.save(context, NaverPlaceLookup.Config())
            runCatching { model.service().cancelVoiceNavigationRequest() }
            saved = NaverPlaceLookup.Config(); id = ""; secret = ""; mode = saved.defaultMode
        } }, modifier = Modifier.fillMaxWidth()) { Text("지역 검색 사용 중지 및 인증 정보 삭제") }
        Note("복수·불일치 결과는 임의 선택하지 않고 네이버지도 검색 화면으로 전환합니다. 앱 호출은 실제 길 안내 시작과 다릅니다.")
    }
}
