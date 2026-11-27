// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject

internal data class ChatRecord(val question: String, val answer: String)
internal data class SelectedContact(val name: String, val uri: String)

internal data class CompanionState(
    val available: Boolean = false,
    val foregroundService: Boolean = false,
    val timerRunning: Boolean = false,
    val stopwatchRunning: Boolean = false,
    val glasses: GlassesState = GlassesState(),
    val conversation: ConversationState = ConversationState(),
    val alwaysOnResumePending: Boolean = false,
    val focusMode: Boolean = false,
    val appNotificationsEnabled: Boolean = true,
    val deviceSettings: DeviceSettingsState = DeviceSettingsState(),
    val content: CompanionContentState = CompanionContentState(),
    val teleprompter: TeleprompterState = TeleprompterState(),
    val agentServer: AgentServerState = AgentServerState(),
    val agentProbe: AgentProbePolicy.State = AgentProbePolicy.State(),
    val recordSync: AgentServerState = AgentServerState(notice = "근거리 동기화가 꺼져 있음"),
    val migration: MigrationState = MigrationState(),
    val phoneEphemeral: Boolean = false,
    val chatContinuity: ChatContinuity.State = ChatContinuity.State(),
    val chatContinuityError: String = "",
    val chatContinuityBusy: Boolean = false,
    val nativeWakeAvailable: Boolean = false,
    val nativeWakeObserved: Boolean = false,
    val devices: List<RayneoConnection.Device> = emptyList(),
    val navigationEntries: List<NavigationFeed.Entry> = emptyList(),
    val navigationEvidence: List<NavigationFeed.SourceEvidence> = emptyList(),
    val navigationSwitchPending: Boolean = false,
    val navigationSwitchStatus: String = "",
    val navigationStatus: String = "네이버지도에서 길 안내를 시작하세요",
    val notificationConnected: Boolean = false,
    val phoneQuestion: String = "",
    val phoneAnswer: String = "",
    val phoneRunning: Boolean = false,
    val voiceAiPending: Boolean = false,
    val phoneStatus: String = "모델을 설정하면 대화를 시작할 수 있습니다",
    val computerQuestion: String = "",
    val computerAnswer: String = "",
    val computerRunning: Boolean = false,
    val computerStatus: String = "컴퓨터가 아직 연결되지 않음",
    val computerConnected: Boolean = false,
    val approvals: List<CodexClient.Request> = emptyList(),
    val agentReviews: List<LocalAgentServer.Review> = emptyList(),
    val agentReceipts: List<LocalAgentServer.Receipt> = emptyList(),
    val logs: List<String> = emptyList(),
    val audioLevels: PromptAudio.State = PromptAudio.State(),
    val captureTimingStatus: String = "기록 시작 후 측정",
    val mediaCaption: MediaCaptionState = MediaCaptionState(),
    val mediaAsrStatus: String = "",
    val mediaFinalCount: Long = 0,
    val mediaTranslation: LocalMediaTranslationState = LocalMediaTranslationState(),
    val translationActive: Boolean = false,
    val captionActive: Boolean = false,
    val captionStatus: String = "실시간 자막 꺼짐",
    val translationStatus: String = "실시간 번역 꺼짐",
    val offlineReady: Boolean = false,
    val offlinePreparing: Boolean = false,
    val offlineStatus: String = "번역 모델 준비 필요",
    val speechPlaying: Boolean = false,
    val speechStatus: String = "음성 출력 대기",
    val wakeActive: Boolean = false,
    val wakePending: Boolean = false,
    val wakeResult: String = "한국어 호출 대기 꺼짐",
    val ambientWakeStatus: String = "호출 대기",
    val nativeWakeStatus: String = "아직 안경 깨우기 이벤트 없음",
    val hostVisibilityStatus: String = "연결 후 전송 대기",
    val recordingVisibilityStatus: String = "연결 후 전송 대기",
    val capabilityStatus: String = "협상 요청 미수신",
    val notificationReadStatus: String = "알림 리스너 서비스 대기 중",
    val notificationSnapshotAt: Long = 0,
    val notificationReadPhase: NotificationReadPhase = NotificationReadPhase.DISCONNECTED,
)

internal class AppModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val preferences = context.getSharedPreferences("companion_ui", Context.MODE_PRIVATE)
    private val settings = AppSettings(context)
    val updateCheck = AppUpdateCheck(context)
    var subscriptionInfo by mutableStateOf(ChatGptSubscription.status())
        private set
    var subscriptionBusy by mutableStateOf(false)
        private set
    var subscriptionSigningIn by mutableStateOf(false)
        private set
    @Volatile private var subscriptionCancelRequested = false
    private val subscriptionWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @SuppressLint(
        "StaticFieldLeak"
    ) // Application-bound service; the binding and observer are released in onCleared.
    private var bridge: BridgeService? = null
    private var bound = false
    private var hostVisible = false
    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        bridge?.setHostVisible(visible)
    }
    var state by mutableStateOf(CompanionState())
        private set

    var config by mutableStateOf(JSONObject())
        private set

    // The recording controller's modelReady is speaker processing initialization, not ASR.
    val speechConfigured: Boolean get() = SpeechRounds.ready(context, config)
    val mobileModelConfigured: Boolean get() = AiModelSelection.endpoint(config) != null

    val aiLegacy get() = AiModelSelection.legacy(config)
    val aiConfigured get() = if (aiLegacy) state.agentServer.enabled else AiModelSelection.direct(config) != null
    val aiReady get() = aiConfigured && (!aiLegacy || state.agentServer.pollRecent)
    val aiStatus get() = if (aiLegacy) state.agentServer.receiverStatus
        else AiModelSelection.label(config) + if (aiConfigured) " · 설정됨 (응답 확인 별도)" else " · 연결 필요"
    val aiSwitchAllowed get() = !subscriptionBusy && !state.conversation.running && !state.conversation.stopping &&
        !state.conversation.summarizing && !state.translationActive && !state.wakeActive && !state.wakePending &&
        !state.phoneRunning && !state.computerRunning && !state.migration.busy &&
        !state.teleprompter.busy && state.teleprompter.active == null
    fun selectAiModel(id: String, profile: AiModelSelection.Profile? = null) = attempt {
        check(aiSwitchAllowed && bridge?.canSwitchAiModel() == true) {
            "진행 중인 AI·기록·번역 작업을 종료한 뒤 모델을 바꾸세요. 기존 기록은 유지됩니다."
        }
        val values = mutableMapOf(AiModelSelection.KEY to id)
        if (profile != null) values[AiModelSelection.PROFILES] = AiModelSelection.withProfile(config, profile)
        val proposed = JSONObject(config.toString())
        values.forEach { (k,v) -> proposed.put(k,v) }
        require(id == AiModelSelection.LEGACY || AiModelSelection.direct(proposed) != null) {
            "선택 모델 연결 정보가 준비되지 않았습니다"
        }
        saveConfig(values)
    }

    fun connectSubscription() = subscriptionAction("ChatGPT 구독 인증", signingIn = true) {
        ChatGptSubscription.initialize(context)
        ChatGptSubscription.signIn { url ->
            main.post {
                if (subscriptionCancelRequested) { ChatGptSubscription.cancelSignIn(); return@post }
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { ChatGptSubscription.cancelSignIn() }
            }
        }
        "ChatGPT 구독 인증 및 모델 목록 확인 완료 · 모델을 선택하세요"
    }
    fun cancelSubscription() { subscriptionCancelRequested = true; ChatGptSubscription.cancelSignIn() }
    fun refreshSubscriptionModels() = subscriptionAction("ChatGPT 모델 목록 조회") {
        ChatGptSubscription.refreshCatalog(); "ChatGPT 계정의 모델 목록을 갱신했습니다"
    }
    fun disconnectSubscription() = subscriptionAction("ChatGPT 구독 연결 해제") {
        if (ChatGptSubscription.signOut()) "ChatGPT 구독 연결 해제 완료"
        else "휴대폰 인증 제거 완료 · 서버 해제 미확인, ChatGPT 설정에서 연결 해제 가능"
    }
    private fun subscriptionAction(label: String, signingIn: Boolean = false, action: () -> String) = attempt {
        check(aiSwitchAllowed && bridge?.canSwitchAiModel() == true) { "기록·AI 작업을 종료한 뒤 인증 설정을 변경하세요" }
        check(ChatGptSubscription.beginMutation()) { "ChatGPT 인증 변경 진행 중" }
        subscriptionCancelRequested = false
        subscriptionSigningIn = signingIn
        subscriptionBusy = true
        try { subscriptionWorker.execute {
            val result = try { runCatching(action) } finally { ChatGptSubscription.endMutation() }
            main.post {
                subscriptionBusy = false
                subscriptionSigningIn = false
                subscriptionInfo = ChatGptSubscription.status()
                refresh()
                result.fold({ notice = it }, {
                    if (subscriptionCancelRequested) notice = "ChatGPT 로그인 취소"
                    else error = "$label 실패 · 계정 권한, 브라우저 및 인터넷 연결을 확인하세요. 기존 API 설정은 변경하지 않았습니다."
                })
            }
        } } catch (failure: Exception) {
            ChatGptSubscription.endMutation(); subscriptionBusy = false; subscriptionSigningIn = false; throw failure
        }
    }

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var bluetoothAllowed by mutableStateOf(false)
        private set

    var installedMaps by mutableStateOf<Set<String>>(emptySet())
        private set
    var notificationAllowed by mutableStateOf(false)
        private set

    var backgroundNotifications by mutableStateOf(false)
        private set

    var onboarding by mutableStateOf(!preferences.getBoolean("onboardingComplete", false))
        private set

    var onboardingStep by mutableIntStateOf(preferences.getInt("onboardingStep", 0))
        private set

    var appearance by mutableStateOf(preferences.getString("appearance", "system") ?: "system")
        private set

    var deviceName by
        mutableStateOf(preferences.getString("deviceName", "RayNeo iO") ?: "RayNeo iO")
        private set

    var phoneDraft by mutableStateOf("")
    var selectedContact by mutableStateOf<SelectedContact?>(null)
    var computerDraft by mutableStateOf("")
    var phoneHistory by mutableStateOf<List<ChatRecord>>(emptyList())
        private set

    var computerHistory by mutableStateOf<List<ChatRecord>>(emptyList())
        private set

    // Form drafts survive activity recreation without writing secrets into saved instance state.
    val formDrafts = mutableStateMapOf<String, String>()
    private val formBaseline = mutableMapOf<String, String>()
    val recommendedPending get() = FeatureDefaults.recommendedChanges { config.optString(it) }.isNotEmpty()
    val recommendedEditable get() = state.available && !state.migration.busy &&
        !state.conversation.running && !state.conversation.stopping && !state.alwaysOnResumePending &&
        !state.nativeWakeAvailable && !state.wakeActive && !state.wakePending && !state.translationActive &&
        !state.phoneRunning && !state.computerRunning && !state.teleprompter.busy && state.teleprompter.active == null &&
        state.glasses.intent == DisplayIntent.Idle && state.glasses.session == null
    fun applyRecommendedSettings() = attempt {
        check(recommendedEditable) { "다른 기능을 종료한 후 권장 설정을 적용하세요" }
        val stored = settings.readStored()
        val changes = FeatureDefaults.recommendedChanges { stored.optString(it) }
        if (changes.isNotEmpty()) saveConfig(changes)
        else notice = "기존 설정을 그대로 유지합니다"
    }
    fun saveVoiceDrafts(keys: List<String>) {
        val changes = FeatureDefaults.changedDrafts(keys, formBaseline, formDrafts)
        if (changes.isNotEmpty()) saveConfig(changes) else notice = "변경된 설정이 없습니다"
    }
    var wantsScan = false
    private val files = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun exportRecording(id: String, uri: android.net.Uri) {
        val s = service(); val record = s.conversations.repository.records.first { it.id == id }
        files.execute {
            val result = runCatching {
                require(!record.transcriptOnly && !record.audioPending && record.audioSamples > 0L) { "내보낼 원본 음성이 없습니다" }
                context.contentResolver.openOutputStream(uri)!!.use { s.conversations.recordings.exportWav(record.audioId ?: id, record.audioSamples, it) }
            }
            main.post { result.fold({ notice = "회의 오디오를 선택한 위치로 내보냈습니다" }, { error = it.message ?: "내보내기 실패" }) }
        }
    }
    private val observer: () -> Unit = { refresh() }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bridge = (binder as BridgeService.LocalBinder).service
                bridge!!.observers.add(observer)
                bridge!!.setHostVisible(hostVisible)
                refresh()
                if (bluetoothAllowed) runCatching { bridge!!.connection.showBonded() }
                if (wantsScan && bluetoothAllowed) {
                    wantsScan = false
                    scan()
                } else if (bluetoothAllowed) bridge?.restoreLastConnection()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bridge = null
                state = CompanionState()
            }
        }

    init {
        attempt { ChatGptSubscription.initialize(context); subscriptionInfo = ChatGptSubscription.status() }
        attempt {
            config = settings.read()
            resetFormDrafts()
        }
        refreshPermissions()
        bound =
            context.bindService(
                Intent(context, BridgeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        if (!bound) error = "연결 서비스를 시작할 수 없습니다. 앱을 다시 열어 주세요"
    }

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            error = failure.message ?: "작업이 완료되지 않았습니다. 다시 시도하세요"
        }
    }

    fun service(): BridgeService = checkNotNull(bridge) { "연결 서비스를 준비 중입니다. 잠시 후 다시 시도하세요" }

    private fun refresh() {
        subscriptionInfo = ChatGptSubscription.status()
        val s = bridge ?: return
        state =
            CompanionState(
                available = true,
                foregroundService = s.foregroundRunning,
                timerRunning = s.timerRunning,
                stopwatchRunning = s.stopwatchRunning,
                glasses = s.state,
                conversation = s.conversations.state,
                alwaysOnResumePending = s.alwaysOnResumePending,
                focusMode = s.focusMode,
                appNotificationsEnabled = s.appNotificationsEnabled,
                deviceSettings = s.deviceSettings.state,
                content = s.content.state,
                teleprompter = s.teleprompter.state,
                agentServer = s.agentServer.state,
                agentProbe = s.agentProbeState,
                recordSync = s.recordSync.state,
                migration = s.migration.state,
                phoneEphemeral = s.agent.ephemeral,
                chatContinuity = s.chatContinuity.state,
                chatContinuityError = s.chatContinuity.error,
                chatContinuityBusy = s.chatContinuityBusy,
                nativeWakeAvailable = s.voiceTransport.nativeActive,
                nativeWakeObserved = s.voiceTransport.nativeWakeObserved,
                devices = s.devices.values.toList(),
                navigationEntries = s.navigation.entries.values.toList(),
                navigationEvidence = s.navigation.evidence,
                navigationSwitchPending = s.navigationSwitchPending,
                navigationSwitchStatus = s.navigationSwitchStatus,
                navigationStatus = s.navigation.status,
                notificationConnected = s.navigation.connected,
                phoneQuestion = s.agent.question,
                phoneAnswer = s.agent.answer,
                phoneRunning = s.agent.running,
                voiceAiPending = s.voiceAiPending,
                phoneStatus = s.agent.status,
                computerQuestion = s.codex.question,
                computerAnswer = s.codex.answer,
                computerRunning = s.codex.running,
                computerStatus = s.codex.status,
                computerConnected = s.codex.connected,
                approvals = s.codex.requests.toList(),
                agentReviews = s.agentServer.reviews,
                agentReceipts = s.agentServer.receipts,
                logs = s.logs.toList(),
                audioLevels = s.audioLevels,
                captureTimingStatus = s.captureTimingStatus,
                offlineReady = s.translator.offline.ready,
                offlinePreparing = s.translator.offline.preparing,
                offlineStatus = s.translator.offline.status,
                speechPlaying = s.speechOutput.speaking,
                speechStatus = s.speechOutput.status,
                mediaCaption = s.mediaCaptionState,
                mediaAsrStatus = s.translator.status,
                mediaFinalCount = s.translator.mediaFinalCount,
                mediaTranslation = s.translator.mediaTranslation.state,
                translationActive = s.translator.active || s.mediaCaptionState.busy,
                wakeActive = s.translator.wakeActive,
                wakePending = s.wakePending,
                wakeResult = if (!s.translator.wakeActive && !s.wakePending && s.wakeResult.startsWith("호출 대기 ·")) s.translator.status else s.wakeResult,
                ambientWakeStatus = s.ambientWakeStatus,
                nativeWakeStatus = s.nativeWakeStatus,
                hostVisibilityStatus = s.hostVisibilityStatus,
                recordingVisibilityStatus = s.recordingVisibilityStatus,
                capabilityStatus = s.capabilityStatus,
                captionActive = s.translator.active && s.translator.captionOnly && !s.translator.wakeActive && !s.mediaCaptionState.busy,
                captionStatus = if (s.mediaCaptionState.busy) "미디어 자막 사용 중" else if (s.translator.captionOnly) s.translator.status else "실시간 자막 꺼짐",
                translationStatus = if (s.translator.captionOnly) "실시간 번역 꺼짐" else s.translator.status,
                notificationReadStatus = s.navigation.listenerState.detail,
                notificationSnapshotAt = s.navigation.lastSnapshotAt,
                notificationReadPhase = s.navigation.listenerState.phase,
            )
    }

    fun refreshPermissions() {
        installedMaps = NavigationFeed.sources.keys.filter { context.packageManager.getLaunchIntentForPackage(it) != null }.toSet()
        bluetoothAllowed =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        notificationAllowed =
            context
                .getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(
                    ComponentName(context, NavigationNotifications::class.java)
                )
        backgroundNotifications =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        bridge?.refreshConnectionPermissions()
        if (bluetoothAllowed && !wantsScan) bridge?.restoreLastConnection()
        NavigationNotifications.refresh(context, foreground = true)
    }

    fun scan() = attempt {
        if (bridge == null) {
            wantsScan = true
            return@attempt
        }
        service().scan()
        service().connection.showBonded()
    }

    fun connect(device: RayneoConnection.Device, diagnostic: Boolean) = attempt {
        service().connect(device, diagnostic)
        deviceName = device.name
        preferences.edit().putString("deviceName", deviceName).apply()
    }

    fun saveConfig(values: Map<String, String>) = attempt {
        check(!state.migration.busy || !values.containsKey("memoryAutoSummary") || !SummaryDispatchPolicy.autoEnabled(values.getValue("memoryAutoSummary"))) { "기록 이전 중에는 자동 요약을 켤 수 없습니다" }
        val stored = settings.readStored()
        val old = settings.effective(stored)
        values.forEach { (key, value) -> stored.put(key, value.trim()) }
        val updated = settings.effective(stored)
        if (values.keys.any { it in setOf("wakePhrases", "wakeSeconds", "wakeFollowSeconds") }) {
            KoreanWakeOptions.parse(updated.optString("wakePhrases", KoreanWakeOptions.DEFAULT_PHRASES),
                updated.optString("wakeSeconds", "120").toIntOrNull() ?: 0,
                updated.optString("wakeFollowSeconds", "12").toIntOrNull() ?: 0)
            check(!state.wakeActive && !state.wakePending) { "호출을 중지한 후 설정을 변경하세요" }
        }
        listOf("nativeAutoAnswer", "ambientWakeEnabled", "ambientWakeContext", "fastRealtimeHints").forEach { key -> values[key]?.let { require(it in setOf("true", "false")) } }
        values["answerLanguage"]?.let { require(KoreanLanguage.answerOptions.any { option -> option.first == it }) { "지원하는 답변 언어를 선택하세요" } }
        values["memorySuggestOnSend"]?.let { require(it in setOf("true", "false")) }
        values["memorySuggestionDays"]?.let { require(it.toIntOrNull() in AutomaticMemoryPolicy.days) }
        values["memorySuggestionLimit"]?.let { require(it.toIntOrNull() in AutomaticMemoryPolicy.limits) }
        values["ttsOutput"]?.let { require(it in setOf("headphones", "media")) { "지원하는 음성 출력 방식을 선택하세요" } }
        values["ttsRate"]?.let { require(it.toFloatOrNull()?.let { n -> n.isFinite() && n in 0.75f..1.5f } == true) { "음성 속도는 0.75~1.5배로 설정하세요" } }
        values["ttsPitch"]?.let { require(it.toFloatOrNull()?.let { n -> n.isFinite() && n in 0.75f..1.25f } == true) { "음성 높이는 0.75~1.25배로 설정하세요" } }
        // The summary/translation endpoint is independent of the DSH conversation.
        // Changing it must not erase explicit chat history or cancel an unrelated task.
        val selectionChanged = AiModelSelection.selected(old) != AiModelSelection.selected(updated) ||
            AiModelSelection.direct(old) != AiModelSelection.direct(updated)
        if (selectionChanged) {
            check(aiSwitchAllowed && bridge?.canSwitchAiModel() == true) { "진행 중인 작업이 있어 모델을 변경하지 않았습니다" }
            require(AiModelSelection.legacy(updated) || AiModelSelection.direct(updated) != null)
        }
        val mobileChanged = old.optString("mobileAgentBackend") != updated.optString("mobileAgentBackend")
        val computerChanged =
            listOf("codexUrl", "codexToken", "codexCwd").any {
                old.optString(it) != updated.optString(it)
            }
        if (computerChanged) { updated.remove("codexThread"); stored.remove("codexThread") }
        updated.remove("amapKey"); stored.remove("amapKey")
        updated.remove("amapConsent"); stored.remove("amapConsent")
        if (mobileChanged) {
            if (bridge != null) service().clearAgent() else ChatContinuityStore.open(context).clear()
        }
        settings.save(stored)
        if (listOf("ambientWakeEnabled", "ambientWakeContext", "wakePhrases", "wakeSeconds", "wakeFollowSeconds", "fastRealtimeHints", "nativeAutoAnswer", "mobileAgentBackend", "answerLanguage").any { old.optString(it) != updated.optString(it) }) bridge?.resetAmbientWake()
        if (listOf("voiceAlwaysOnStart", "nativeAutoAnswer", "audioRetention", "asrEngine", "asrKey", "asrUrl", "asrModel", "asrLanguage").any { old.optString(it) != updated.optString(it) }) bridge?.cancelVoiceAlwaysOnStart()
        if (old.optString("fastRealtimeHints") != updated.optString("fastRealtimeHints")) bridge?.cancelRealtimeStream()
        if (listOf("audioRetention", "asrEngine").any { old.optString(it) != updated.optString(it) }) bridge?.cancelAlwaysOnRecovery()
        if (mobileChanged) phoneHistory = emptyList()
        if (computerChanged) {
            bridge?.closeCodex()
            computerHistory = emptyList()
        }
        config = updated
        if (selectionChanged) bridge?.aiModelChanged()
        bridge?.conversations?.configurationChanged()
        bridge?.let { LocalModels.keepResident(it, updated) }
        resetFormDrafts()
        notice = "설정이 암호화되어 저장됨"
    }

    private fun resetFormDrafts() {
        configurationKeys.forEach {
            formDrafts[it] = when (it) {
                "asrEngine" -> SpeechRounds.engine(config)
                "asrLanguage" -> SpeechRounds.languageHints(config)
                "phoneSpeechLanguage" -> KoreanLanguage.phoneInput(config.optString(it))
                "answerLanguage" -> KoreanLanguage.answerLanguage(config.optString(it))
                "memorySuggestOnSend" -> config.optBoolean(it).toString()
                "memorySuggestionDays" -> config.optString(it).toIntOrNull()?.takeIf { n -> n in AutomaticMemoryPolicy.days }?.toString() ?: "30"
                "memorySuggestionLimit" -> config.optString(it).toIntOrNull()?.takeIf { n -> n in AutomaticMemoryPolicy.limits }?.toString() ?: "3"
                "ttsOutput" -> config.optString(it).takeIf { value -> value in setOf("headphones", "media") } ?: "headphones"
                "ttsRate", "ttsPitch" -> config.optString(it).ifBlank { "1.0" }
                else -> config.optString(it)
            }
        }
        formBaseline.clear(); formBaseline.putAll(formDrafts)
    }

    fun importConfig(data: JSONObject) {
        val keys = configurationKeys
        saveConfig(keys.filter { data.has(it) }.associateWith { data.getString(it) })
    }
    companion object {
        val configurationKeys = listOf("modelUrl", "modelName", "modelKey", "mobileAgentBackend", "localAgentPort", "codexUrl", "codexCwd", "codexToken",
            "asrEngine", "asrKey", "asrUrl", "asrModel", "asrLanguage", "phoneSpeechLanguage", "answerLanguage", "ttsRate", "ttsPitch", "ttsOutput", "memorySuggestOnSend", "memorySuggestionDays", "memorySuggestionLimit", "wakePhrases", "wakeSeconds", "wakeFollowSeconds", "ambientWakeEnabled", "ambientWakeContext", "offlineTranslationUrl", "offlineTranslationModel", "offlineTranslationKey", "decisionKey", "decisionUrl", "decisionModel", "nativeAutoAnswer", "voiceAlwaysOnStart", "fastRealtimeHints", "audioChannel",
            "speakerThreshold", "voiceRoutingPrompt", "memoryPrompt", "memoryAutoSummary", "answerClearSeconds", "answerMaxChars", "saveMeetingAudio", "audioRetention", "glassesVoiceGate", "selfVoiceMarginDb", "selfVoiceFloorDb", "selfVoiceHoldMs", "bargeInLevelDb", "bargeInMinMs", "asrKeepLoaded")
    }

    fun send(source: AnswerSource, executeTask: Boolean = false) = attempt {
        val mobile = source == AnswerSource.MOBILE_AGENT
        val text = (if (mobile) phoneDraft else computerDraft).trim()
        require(text.isNotBlank()) { "먼저 질문을 입력하세요" }
        val old =
            if (mobile) ChatRecord(state.phoneQuestion, state.phoneAnswer)
            else ChatRecord(state.computerQuestion, state.computerAnswer)
        val oldWasEphemeral = state.phoneEphemeral
        service().submitAnswer(source, text, allowTools = !mobile || executeTask)
        if (mobile) {
            if (old.question.isNotBlank() && !oldWasEphemeral) phoneHistory = (phoneHistory + old).takeLast(30)
            phoneDraft = ""
        } else {
            if (old.question.isNotBlank()) computerHistory = (computerHistory + old).takeLast(30)
            computerDraft = ""
        }
    }

    fun sendMemoryQuestion(question: String, selected: MemoryReferencePolicy.Result, profile: Map<String, String> = emptyMap()): Boolean {
        var submitted = false
        attempt {
            val old = ChatRecord(state.phoneQuestion, state.phoneAnswer)
            val oldWasEphemeral = state.phoneEphemeral
            service().submitMemoryQuestion(question.trim(), selected, profile)
            if (old.question.isNotBlank() && !oldWasEphemeral) phoneHistory = (phoneHistory + old).takeLast(30)
            phoneDraft = ""
            submitted = true
        }
        return submitted
    }

    fun newConversation(source: AnswerSource) = attempt {
        if (source == AnswerSource.MOBILE_AGENT) {
            service().newMobileConversation()
            phoneHistory = emptyList()
        } else {
            service().newCodexConversation()
            computerHistory = emptyList()
        }
    }

    fun step(value: Int) {
        onboardingStep = value.coerceIn(0, 4)
        preferences.edit().putInt("onboardingStep", onboardingStep).apply()
    }

    fun finishSetup() {
        onboarding = false
        preferences.edit().putBoolean("onboardingComplete", true).apply()
    }

    fun replaySetup() {
        step(0)
        onboarding = true
    }

    fun changeAppearance(value: String) {
        appearance = value
        preferences.edit().putString("appearance", value).apply()
    }

    override fun onCleared() {
        cancelSubscription()
        subscriptionWorker.shutdown()
        updateCheck.close()
        bridge?.observers?.remove(observer)
        if (bound) context.unbindService(connection)
        files.shutdown()
        bridge = null
        bound = false
    }
}
