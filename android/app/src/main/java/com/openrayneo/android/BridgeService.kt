// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.time.LocalTime

internal class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@BridgeService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    val observers = mutableSetOf<() -> Unit>()
    val devices = linkedMapOf<String, RayneoConnection.Device>()
    val logs = ArrayDeque<String>()
    lateinit var settings: AppSettings; private set
    lateinit var conversations: ConversationController; private set
    lateinit var voiceTransport: GlassesVoiceTransport; private set
    lateinit var requests: GlassesRequests; private set
    lateinit var deviceSettings: GlassesDeviceSettings; private set
    private val bargeMeter = BargeInMeter()
    lateinit var teleprompter: TeleprompterLibrary; private set
    lateinit var agentServer: LocalAgentServer; private set
    lateinit var recordSync: RecordSyncServer; private set
    lateinit var content: CompanionContent; private set
    private lateinit var supervisor: ConnectionSupervisor
    val connection get() = supervisor.transport
    private lateinit var display: GlassesDisplay
    private lateinit var audio: PromptAudio
    private lateinit var voiceAudio: PromptAudio
    val navigation get() = NavigationFeed
    lateinit var agent: MobileAgent; private set
    lateinit var codex: CodexClient; private set
    lateinit var translator: LiveTranslator; private set
    var state = GlassesState(); private set
    val status get() = state.link.detail
    val ready get() = state.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val busy get() = state.link.phase in setOf(LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    val displayStatus get() = state.displayDescription
    val audioStatus get() = state.audio.detail
    val audioLevels get() = if (voiceAudio.state.active) voiceAudio.state else audio.state
    private val events = ArrayDeque<GlassesEvent>()
    private var dispatching = false
    private var destroyed = false
    private var initialized = false
    private var foreground = false
    private var stopAfterDisconnect = false
    private var restoreTask: Runnable? = null
    private var autoClearFor: AnswerRef? = null
    private var clearAnswerTask: Runnable? = null
    private var closeTask: Runnable? = null
    private var audioOwner: Long? = null
    private var nextAnswer = 0L
    private val serviceInstance = java.util.UUID.randomUUID().toString()
    private var latestRealtimeRequest = 0L
    private var realtimeStreamId: String? = null
    private var realtimePreviewRequest = 0L
    private var realtimePreviewRevision = -1L
    private val proactiveHintGate = ProactiveHintPolicy.DisplayGate()
    private val pendingAnswers = mutableMapOf<AnswerSource, AnswerPermit>()
    private data class VoiceDelivery(val ref: AnswerRef, val source: ConversationSource, val wakeId: String?)
    private var voiceReply: VoiceDelivery? = null
    private var conversationOwnsDisplay = false
    private var resumeAlwaysOn: ConversationMode? = null
    private val reconnectCapture = AlwaysOnReconnectPolicy()
    val alwaysOnResumePending: Boolean get() = reconnectCapture.pending
    private var notificationText = ""
    private var notificationReadStatus = ""
    private val navigationObserver: () -> Unit = {
        if (notificationReadStatus != navigation.listenerState.detail) {
            notificationReadStatus = navigation.listenerState.detail
            onLog(notificationReadStatus)
        }
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
    }
    private val notificationRefresh = object : Runnable {
        override fun run() {
            if (navigation.enabled) NavigationNotifications.refresh(this@BridgeService)
            content.automaticWeather()
            main.postDelayed(this, 30_000)
        }
    }
    private val audioRefresh = object : Runnable {
        override fun run() {
            audioOwner?.let { dispatch(GlassesEvent.AudioChanged(it, audio.state.coordinatorState())) }
            if (conversations.state.running) audioLevels.error?.let { conversations.transportError(it) }
            if (reconnectCapture.expire(SystemClock.elapsedRealtime())) { updateNotification(); notifyUI() }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        LocalModels.keepResident(this, settings.read())
        supervisor = ConnectionSupervisor(this, main,
            { dispatch(GlassesEvent.LinkChanged(it)) }, { devices[it.bluetooth.address] = it; notifyUI() },
            { frame, source -> runCatching {
                teleprompter.observe(frame)
                requests.receive(frame)
                // Assistant control events (not audio) go to the app log, to see how the firmware behaves around barge-in.
                if (frame.protocol == 0x0d) ProtocolCodec.message(frame.payload).let { m ->
                    if (m.type !in setOf(3, 163, 164)) onLog("음성 이벤트 수신 type=${m.type} ${m.json.orEmpty().take(160)}")
                }
                voiceTransport.receive(frame)
                display.receive(frame, source)
            }.onFailure { onLog("안경 메시지 파싱 실패: $source") } }, ::onLog)
        requests = GlassesRequests(main, connection::sendFrame)
        deviceSettings = GlassesDeviceSettings(requests, ::notifyUI)
        content = CompanionContent(this, requests, { connection.resolvedClassicAddress?.takeIf { state.link.phase == LinkPhase.FULL } }, ::notifyUI)
        teleprompter = TeleprompterLibrary(this, requests, ::notifyUI)
        agentServer = LocalAgentServer(main, settings, ::notifyUI, {
            JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("commit", BuildConfig.GIT_COMMIT)
                .put("phoneId", nativeIdentity().getString("phoneId")).put("link", state.link.phase.name).put("display", state.displayDescription).put("conversationRunning", conversations.state.running)
        }, {
            org.json.JSONArray(conversations.repository.records.map { it.json().apply { remove("speakerSamples") } })
        }, { name, args, allowed, completion -> requests.withPermission(allowed) { executeTool(name, args, completion) } }, { agent.harness }, ::recordAPI)
        requests.observe = { protocol, type, body ->
            when (protocol) { 15 -> deviceSettings.observe(type, body); 22 -> content.observe(type, body) }
        }
        conversations = ConversationController(this, settings, ::notifyUI, ::conversationCapture, ::submitVoiceRequest,
            { segment, source ->
                if (source == ConversationSource.NATIVE) segment.roundId?.let { voiceTransport.asr(segment.text, it) }
                // Realtime hint: show what was just heard so the wearer knows the glasses are listening.
                if (source == ConversationSource.REALTIME && conversationOwnsDisplay) {
                    cancelRealtimeStream()
                    dispatch(GlassesEvent.StandbyText(TranslationView(LiveTranslator.clip(segment.text, 80), "듣는 중 · 답이 필요하면 여기에 표시됩니다")))
                }
            }, ::onConversationStopped)
        conversations.partial = { text, wake -> main.post { voiceTransport.partial(text, wake) } }
        recordSync = RecordSyncServer(settings, conversations.repository, conversations.recordings, main, ::notifyUI)
        if (settings.read().optBoolean("recordSyncEnabled")) recordSync.start()
        voiceAudio = PromptAudio { left, right ->
            if (bargeMeter.feed(left)) main.post { voiceTransport.voiceDuringAnswer() }
            conversations.accept(left, right)
        }
        voiceTransport = GlassesVoiceTransport(connection::sendFrame, ::nativeWake, {
            voiceAudio.stop(); conversations.voiceRoundBoundary(); voiceAudio.start(1); conversations.nextRound()
        }, { ended ->
            if (ended) conversations.stop() else { voiceAudio.stop(); conversations.voiceRoundBoundary(); conversations.endRound() }
        }, { voiceAudio.accept(it) }, { conversations.voiceActivity(it) }, { conversations.transportError(it) }, ::onLog)
        // Only when the posted next turn will really start (auto answer on, native session running); otherwise capture closes as before.
        voiceTransport.answeringChanged = { on ->
            val cfg = settings.read()
            if (on) bargeMeter.arm(cfg.optString("bargeInLevelDb").toDoubleOrNull() ?: -30.0, cfg.optString("bargeInMinMs").toIntOrNull() ?: 250)
            else { bargeMeter.disarm(); onLog("대화 끊기 진단: 답변 중 마이크 최대 ${bargeMeter.peakDb.toInt()} dBFS (감지 기준 ${cfg.optString("bargeInLevelDb").ifBlank { "-30" }} dBFS)") }
        }
        voiceTransport.bargeIn = { deviceSettings.state.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("workMode") == true &&
            settings.read().optBoolean("nativeAutoAnswer") && conversations.state.running && conversations.state.source == ConversationSource.NATIVE }
        translator = LiveTranslator(this, main, { source, target -> dispatch(GlassesEvent.TranslationText(TranslationView(source, target))) }, ::notifyUI) {
            if (state.intent == DisplayIntent.Translation) dispatch(GlassesEvent.PauseDisplay)
        }
        audio = PromptAudio { left, right ->
            if (translator.active) translator.accept(left, right)
            else if (conversations.state.running && conversations.state.source == ConversationSource.REALTIME) conversations.accept(left, right)
        }
        display = GlassesDisplay(connection::sendFrame,
            { task, delay -> main.postDelayed(task, delay) }, { main.removeCallbacks(it) }, ::onLog,
            { id, signal -> dispatch(GlassesEvent.SessionSignal(id, signal)) },
            { id, packet -> if (audioOwner == id && state.session?.id == id) audio.accept(packet) },
            { dispatch(GlassesEvent.ExternalPage(it)) })
        navigation.initialize(this)
        navigation.stop()
        navigation.observers.add(navigationObserver)
        agent = MobileAgent(settings, conversations.repository, { question, answer, input -> deliverExternalVoice(question, answer, input) }, ::notifyUI, { name, args, allowed, completion -> requests.withPermission(allowed) { executeTool(name, args, completion) } }) { question, answer -> completed(AnswerSource.MOBILE_AGENT, question, answer) }
        if (settings.read().optBoolean("localAgentEnabled")) agentServer.start()
        codex = CodexClient(settings, ::notifyUI) { question, answer -> completed(AnswerSource.COMPUTER_CODEX, question, answer) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("bridge", "안경 연결 및 내비게이션", NotificationManager.IMPORTANCE_LOW))
        initialized = true
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
        NavigationNotifications.refresh(this)
        main.postDelayed(notificationRefresh, 30_000)
        main.postDelayed(audioRefresh, 1_000)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cancelAnswer(AnswerSource.MOBILE_AGENT); closeCodex()
            dispatch(GlassesEvent.StopNavigation)
            stopAfterDisconnect = true
            disconnect()
        } else {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (PhoneLocation.allowed(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            foreground = true
        }
        return START_NOT_STICKY
    }

    fun enableLocationWhileVisible() {
        if (foreground && PhoneLocation.allowed(this))
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun dispatch(event: GlassesEvent) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dispatch(event) }; return }
        events.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (events.isNotEmpty()) {
                val transition = GlassesCoordinator.reduce(state, events.removeFirst())
                val previous = state
                state = transition.state
                if (previous.link.phase == LinkPhase.FULL && state.link.phase != LinkPhase.FULL) {
                    val active = conversations.state
                    reconnectCapture.linkLost(foreground && !previous.disconnecting && !state.disconnecting &&
                        active.running && !active.stopping && active.mode == ConversationMode.DAILY &&
                        active.source == ConversationSource.ALWAYS_ON && voiceTransport.source == ConversationSource.ALWAYS_ON,
                        SystemClock.elapsedRealtime())
                    resumeAlwaysOn = null
                    conversations.stop(); voiceTransport.disconnected(); voiceAudio.stop()
                    requests.disconnected(); deviceSettings.disconnected(); content.disconnected(); teleprompter.disconnected()
                }
                if (previous.link.phase != LinkPhase.FULL && state.link.phase == LinkPhase.FULL) {
                    content.connected(); runCatching { deviceSettings.refresh() }
                    if (reconnectCapture.pending) main.post { tryResumeAlwaysOn() }
                }
                if (previous.session != null && state.session == null && previous.session.mode == PageMode.PROMPT && conversations.state.running && conversations.state.source == ConversationSource.REALTIME && conversationOwnsDisplay &&
                    (state.ownership != DisplayOwnership.APP || state.intent == DisplayIntent.Idle || state.intent == DisplayIntent.Navigation || state.error != null)) {
                    conversationOwnsDisplay = false; conversations.stop()
                }
                // The translator runs only while the glasses show its window; any other page, link loss or yield stops it.
                // On link loss the intent would otherwise survive and reopen an orphan window after reconnecting.
                if (state.intent == DisplayIntent.Translation && (state.link.phase != LinkPhase.FULL || state.ownership != DisplayOwnership.APP)) {
                    translator.stop(); events.add(GlassesEvent.PauseDisplay)
                } else if (translator.active && state.intent != DisplayIntent.Translation) translator.stop()
                updateAnswerClearTimer()
                if (state.displayDescription != previous.displayDescription) onLog(state.displayDescription)
                transition.effects.forEach { effect ->
                    runCatching { execute(effect) }.onFailure { error ->
                        val message = error.message ?: "안경 작업 실패"
                        onLog(message)
                        when (effect) {
                            is GlassesEffect.StartPage -> events.add(GlassesEvent.SessionSignal(effect.session.id, PageSignal.Failed(message)))
                            is GlassesEffect.UpdatePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Failed(message)))
                            is GlassesEffect.ClosePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Closed(message)))
                            is GlassesEffect.StartAudio -> events.add(GlassesEvent.AudioChanged(effect.id, AudioState(AudioPhase.FAILED, message)))
                            is GlassesEffect.FollowNavigation -> events.add(GlassesEvent.StopNavigation)
                            else -> Unit
                        }
                    }
                }
            }
        } finally { dispatching = false }
        updateNotification(); notifyUI()
        if (stopAfterDisconnect && state.link.phase == LinkPhase.DISCONNECTED && !state.disconnecting) {
            stopAfterDisconnect = false
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun execute(effect: GlassesEffect) {
        when (effect) {
            is GlassesEffect.StartPage -> display.start(effect.session)
            is GlassesEffect.UpdatePage -> display.update(effect.id, effect.content)
            is GlassesEffect.ClosePage -> display.stop(effect.id)
            GlassesEffect.ResetPage -> display.reset()
            is GlassesEffect.StartAudio -> { audioOwner = effect.id; audio.start(effect.channels) }
            GlassesEffect.StopAudio -> { audioOwner = null; audio.stop() }
            is GlassesEffect.FollowNavigation -> navigation.follow(effect.key)
            GlassesEffect.StopFollowing -> navigation.stop()
            is GlassesEffect.ScheduleRestore -> {
                restoreTask?.let(main::removeCallbacks)
                restoreTask = Runnable { dispatch(GlassesEvent.RestoreAnswer(effect.id, effect.revision, effect.ref)) }
                    .also { main.postDelayed(it, 12_000) }
            }
            GlassesEffect.CancelRestore -> { restoreTask?.let(main::removeCallbacks); restoreTask = null }
            is GlassesEffect.ScheduleCloseTimeout -> {
                closeTask?.let(main::removeCallbacks)
                closeTask = Runnable { dispatch(GlassesEvent.CloseTimedOut(effect.id)) }.also { main.postDelayed(it, 1_500) }
            }
            GlassesEffect.CancelCloseTimeout -> { closeTask?.let(main::removeCallbacks); closeTask = null }
            GlassesEffect.DisconnectTransport -> supervisor.disconnect()
        }
    }

    fun scan() {
        check(!ready && state.link.phase != LinkPhase.CONNECTING && !state.disconnecting) { "먼저 현재 연결을 끊으세요" }
        cancelAlwaysOnRecovery(); devices.clear(); supervisor.scan()
    }
    fun connect(device: RayneoConnection.Device, bleOnly: Boolean) {
        check(!ready && !state.disconnecting) { "먼저 현재 연결을 끊으세요" }
        cancelAlwaysOnRecovery()
        startForegroundService(Intent(this, BridgeService::class.java))
        dispatch(GlassesEvent.BeginConnection)
        runCatching { supervisor.connect(device, bleOnly) }.onFailure {
            supervisor.disconnect(); onLog(it.message ?: "연결 실패")
        }
    }
    fun disconnect() {
        supervisor.cancelRetries()
        stopConversation()
        dispatch(GlassesEvent.Disconnect)
    }
    fun followNavigation(key: String) {
        cancelAlwaysOnRecovery()
        conversationOwnsDisplay = false
        if (conversations.state.running) conversations.stop()
        check(!state.disconnecting) { "연결 해제가 끝날 때까지 기다리세요" }
        val entry = checkNotNull(navigation.entries[key]) { "해당 알림이 사라졌습니다. Amap에서 내비게이션을 시작한 뒤 새로 고치세요" }
        val frame = entry.instruction?.let { NavigationFrame.render(it.direction, it.meters, entry.title) }
            ?: NavigationFrame.status("안내 미인식", "Amap 확인")
        dispatch(GlassesEvent.SelectNavigation(NavigationState(true, key, frame)))
    }
    fun stopNavigation() = dispatch(GlassesEvent.StopNavigation)
    fun reopenNavigation() {
        val key = navigation.selectedKey ?: error("먼저 따라갈 Amap 알림을 선택하세요")
        followNavigation(key)
    }
    fun clearDisplay() { autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null; dispatch(GlassesEvent.ClearDisplay) }
    fun enableAgentServer(enabled: Boolean) {
        if (enabled) {
            check(checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "먼저 근처 기기 권한을 허용한 뒤 로컬 Agent 엔드포인트를 활성화하세요"
            }
            agentServer.start()
            startForegroundService(Intent(this, BridgeService::class.java))
        } else agentServer.stop()
    }
    fun startTeleprompter(title: String, text: String, speed: Int, documentId: String? = null, completion: (Result<JSONObject>) -> Unit) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        cancelAlwaysOnRecovery()
        conversationOwnsDisplay = false; conversations.stop(); dispatch(GlassesEvent.PauseDisplay)
        val allowed = requests.permission()
        val deadline = SystemClock.elapsedRealtime() + 2_500
        val wait = object : Runnable {
            override fun run() {
                if (!allowed() || state.link.phase != LinkPhase.FULL || state.disconnecting) { completion(Result.failure(IllegalStateException("안경 연결이 끊김"))); return }
                if (state.session == null) {
                    val id = teleprompter.save(title, text, documentId ?: java.util.UUID.randomUUID().toString())
                    requests.withPermission(allowed) { teleprompter.start(id, speed, completion) }
                } else if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 50)
                else completion(Result.failure(IllegalStateException("이전 표시가 아직 끝나지 않았습니다. 잠시 후 다시 재생하세요")))
            }
        }; main.post(wait)
    }
    fun startConversation(mode: ConversationMode, source: ConversationSource, target: VoicePerson? = null,
                          resumeOnReconnect: Boolean = false) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        check(!resumeOnReconnect || (foreground && mode == ConversationMode.DAILY && source == ConversationSource.ALWAYS_ON)) {
            "종일 일상 기록의 재연결 복구는 연결 서비스가 실행 중일 때만 허용됩니다"
        }
        cancelAlwaysOnRecovery()
        conversations.start(mode, source, target, if (source == ConversationSource.NATIVE) voiceTransport.wakeId else null)
        if (resumeOnReconnect) reconnectCapture.arm()
    }
    fun cancelAlwaysOnRecovery() {
        val wasPending = reconnectCapture.pending
        reconnectCapture.cancel()
        if (wasPending) { updateNotification(); notifyUI() }
    }
    fun stopConversation() {
        cancelAlwaysOnRecovery()
        resumeAlwaysOn = null
        conversations.stop()
    }
    fun retranscribe(recordId: String) {
        cancelAlwaysOnRecovery()
        conversations.retranscribe(recordId)
    }
    private fun onConversationStopped() {
        reconnectCapture.recordStopped()
        if (reconnectCapture.pending) tryResumeAlwaysOn() else resumeBackground()
    }
    private fun tryResumeAlwaysOn() {
        if (!reconnectCapture.pending) return
        val now = SystemClock.elapsedRealtime()
        if (reconnectCapture.expire(now) || destroyed || !foreground || state.disconnecting) {
            cancelAlwaysOnRecovery(); updateNotification(); notifyUI(); return
        }
        if (state.link.phase != LinkPhase.FULL) return
        if (state.intent != DisplayIntent.Idle || translator.active || voiceTransport.nativeActive) {
            cancelAlwaysOnRecovery(); return
        }
        if (!reconnectCapture.take(now, true, !conversations.state.running && voiceTransport.source == null && state.session == null)) return
        runCatching { startConversation(ConversationMode.DAILY, ConversationSource.ALWAYS_ON, resumeOnReconnect = true) }
            .onSuccess { onLog("종일 기록 재연결 복구 요청") }
            .onFailure { conversations.report(it.message ?: "종일 기록 재개 실패") }
        updateNotification(); notifyUI()
    }
    private fun nativeWake() {
        notifyUI()
        if (!settings.read().optBoolean("nativeAutoAnswer")) return
        if (state.link.phase != LinkPhase.FULL || state.disconnecting) return
        cancelAlwaysOnRecovery()
        if (conversations.state.running) {
            if (conversations.state.source == ConversationSource.ALWAYS_ON) {
                resumeAlwaysOn = conversations.state.mode.takeUnless { conversations.state.stopping }
                val wake = voiceTransport.wakeId
                conversations.handoffForWake {
                    if (voiceTransport.nativeActive && voiceTransport.wakeId == wake && state.link.phase == LinkPhase.FULL && !state.disconnecting)
                        startConversation(ConversationMode.DAILY, ConversationSource.NATIVE)
                    else resumeBackground()
                }
                return
            }
            if (conversations.state.source != ConversationSource.NATIVE) return
            conversations.nextRound(voiceTransport.wakeId); agent.harness.cancel("route"); agent.harness.cancel("hint"); voiceAudio.stop(); voiceAudio.start(1); voiceTransport.start(ConversationSource.NATIVE)
        } else {
            try { startConversation(ConversationMode.DAILY, ConversationSource.NATIVE) }
            catch (error: Exception) { conversations.report(error.message ?: "기본 응답 수신 실패") }
        }
    }
    private fun resumeBackground() {
        val mode = resumeAlwaysOn ?: return
        if (destroyed || state.disconnecting || state.link.phase != LinkPhase.FULL) { resumeAlwaysOn = null; return }
        if (voiceTransport.nativeActive || conversations.state.running) return
        resumeAlwaysOn = null
        startConversation(mode, ConversationSource.ALWAYS_ON)
    }
    private fun conversationCapture(source: ConversationSource, start: Boolean) {
        if (start) {
            check(!destroyed && state.link.phase == LinkPhase.FULL && !state.disconnecting && !conversations.state.stopping) {
                "음성 수신 시작 전에 안경 연결 또는 기록 세션이 종료됨"
            }
            when (source) {
                ConversationSource.REALTIME -> { conversationOwnsDisplay = true; dispatch(GlassesEvent.OpenStandby) }
                ConversationSource.NATIVE, ConversationSource.ALWAYS_ON -> {
                    conversationOwnsDisplay = false
                    dispatch(GlassesEvent.PauseDisplay); voiceAudio.start(1); voiceTransport.start(source)
                }
            }
        } else {
            reconnectCapture.captureStopped()
            cancelRealtimeStream()
            if (source == ConversationSource.NATIVE && voiceTransport.nativeActive) resumeAlwaysOn = null
            agent.harness.cancel("route"); agent.harness.cancel("hint")
            if (source == ConversationSource.REALTIME) {
                if (conversationOwnsDisplay) dispatch(GlassesEvent.PauseDisplay)
                conversationOwnsDisplay = false
            } else { voiceAudio.stop(); voiceTransport.stop() }
        }
    }
    private fun submitVoiceRequest(request: VoiceRequest): Boolean? {
        val identity = nativeIdentity()
        val input = JSONObject().put("source", request.source.name).put("wakeId", request.wakeId)
            .put("recordId", request.recordId).put("sourceRevision", request.sourceRevision).put("backfill", request.backfill)
            .put("contextKey", identity.getString("conversationKey")).put("serviceInstance", serviceInstance)
        if (request.source == ConversationSource.NATIVE && !request.backfill) {
            input.put("phoneId", identity.getString("phoneId")).put("conversationKey", identity.getString("conversationKey"))
        }
        if (request.source == ConversationSource.REALTIME) {
            cancelRealtimeStream()
            latestRealtimeRequest = ++nextAnswer
            input.put("displayRevision", state.revision).put("realtimeRequest", latestRealtimeRequest)
        }
        // Exact local commands are consumed even if their native wake has expired.
        // Check permission again when the serialized operation actually sends each wire command.
        val localCommand = LocalDeviceQuery.parse(request.text, request.source, request.backfill)
        if (localCommand != null) {
            val wake = request.wakeId
            val contextKey = identity.getString("conversationKey")
            val inheritedPermission = requests.permission()
            val allowed = {
                !destroyed && state.link.phase == LinkPhase.FULL && !state.disconnecting &&
                    wake != null && wake == voiceTransport.wakeId && voiceTransport.nativeActive &&
                    runCatching { inheritedPermission() && settings.read().optString("nativeConversationKey") == contextKey }.getOrDefault(false)
            }
            if (allowed()) {
                input.put("derivedId", java.util.UUID.randomUUID().toString())
                requests.withPermission(allowed) {
                    requests.perform({ deviceSettings.executeVoiceCommand(localCommand) }) { result ->
                        if (allowed()) {
                            val answer = result.getOrElse {
                                if (localCommand.kind == LocalDeviceQuery.Kind.BATTERY) LocalDeviceQuery.batteryAnswer(null)
                                else "안경 밝기 처리 결과를 확인하지 못했습니다."
                            }
                            deliverExternalVoice(request.text, answer, input)
                        }
                    }
                }
            }
            return true
        }
        // The glasses window shows only a few lines; ask the Agent to fit them (0 = no limit).
        val maxChars = settings.read().optString("answerMaxChars").toIntOrNull() ?: DEFAULT_ANSWER_MAX_CHARS
        val limit = if (maxChars > 0) "\n[안경 표시] 답변은 ${maxChars}자 이내. 더 필요하면 요약하고, 자세히 요청받을 때만 이어서 말한다." else ""
        // Optional fast mode uses the existing standby page; never opens or steals a page.
        if (request.source == ConversationSource.REALTIME && !request.backfill && settings.read().optBoolean("fastRealtimeHints")) {
            val payload = request.hintContext
            val lifetime = ProactiveHintPolicy.remainingForSpeech(System.currentTimeMillis(), request.lastSpeechAt)
            if (payload == null || lifetime == 0L || request.recordId.isNullOrBlank()) return true
            input.put("streamHint", true).put("voiceContext", payload)
                .put("hintDeadline", SystemClock.elapsedRealtime() + lifetime)
            if (!isRealtimeStreamCurrent(input)) return true
            realtimeStreamId = agent.harness.submit(request.text, conversations.modePolicy.proactiveHintPrompt + limit,
                kind = "hint", input = input, hintLifetimeMs = lifetime, preview = { text ->
                    if (isRealtimeStreamCurrent(input)) {
                        realtimePreviewRequest = input.optLong("realtimeRequest")
                        realtimePreviewRevision = state.revision
                        dispatch(GlassesEvent.StandbyText(TranslationView("AI 힌트 · 작성 중", text)))
                    }
                }) { result -> finishRealtimeStream(input, result) }
            return true
        }
        // Passive AlwaysOn suggestions reuse the existing tool-free DSH hint path, not durable Agent tasks.
        if (request.source == ConversationSource.ALWAYS_ON && !request.backfill) {
            val active = conversations.state
            val payload = request.hintContext
            val lifetime = ProactiveHintPolicy.remainingForSpeech(System.currentTimeMillis(), request.lastSpeechAt)
            if (payload == null || lifetime == 0L || request.recordId.isNullOrBlank() ||
                !active.running || active.stopping || active.activeRecord?.id != request.recordId ||
                active.source != ConversationSource.ALWAYS_ON || destroyed || state.disconnecting ||
                state.link.phase != LinkPhase.FULL || voiceTransport.source != ConversationSource.ALWAYS_ON) return true
            input.put("voiceContext", payload).put("hintSequence", ++nextAnswer)
                .put("hintDeadline", SystemClock.elapsedRealtime() + lifetime)
            agent.harness.submit(request.text, conversations.modePolicy.proactiveHintPrompt + limit,
                kind = "hint", input = input, hintLifetimeMs = lifetime) { result ->
                result.onSuccess { answer -> deliverExternalVoice(request.text, answer, input) }
            }
            return true
        }
        // The relay's encrypted outbox accepts work even while DSH or the network is unavailable.
        agent.harness.submit(request.text, request.context + limit, input = input) { result ->
            result.fold({ answer -> deliverExternalVoice(request.text, answer, input) }, { conversations.report(it.message ?: "외부 Agent 작업이 완료되지 않음") })
        }
        return true
    }
    private fun isRealtimeStreamCurrent(input: JSONObject): Boolean {
        val active = conversations.state
        return input.optBoolean("streamHint") && !destroyed && !state.disconnecting &&
            state.link.phase == LinkPhase.FULL && state.ownership == DisplayOwnership.APP &&
            state.intent == DisplayIntent.PromptStandby && conversationOwnsDisplay &&
            active.running && !active.stopping && active.source == ConversationSource.REALTIME &&
            active.activeRecord?.id == input.optString("recordId") &&
            input.optString("serviceInstance") == serviceInstance &&
            input.optString("contextKey") == settings.read().optString("nativeConversationKey") &&
            input.optLong("realtimeRequest") == latestRealtimeRequest && input.optLong("displayRevision", -1) == state.revision &&
            ProactiveHintPolicy.remaining(SystemClock.elapsedRealtime(), input.optLong("hintDeadline")) > 0L &&
            settings.read().optBoolean("fastRealtimeHints")
    }
    fun cancelRealtimeStream() {
        val id = realtimeStreamId
        val preview = realtimePreviewRequest != 0L
        val ownsPreview = preview && realtimePreviewRevision == state.revision
        realtimeStreamId = null; realtimePreviewRequest = 0L; realtimePreviewRevision = -1L
        if (id != null || preview) latestRealtimeRequest = ++nextAnswer
        if (id != null) agent.harness.cancelRequest(id)
        if (ownsPreview && !destroyed && state.link.phase == LinkPhase.FULL && !state.disconnecting &&
            state.intent == DisplayIntent.PromptStandby && state.ownership == DisplayOwnership.APP && conversationOwnsDisplay)
            dispatch(GlassesEvent.StandbyText(TranslationView("AI 힌트", "듣는 중")))
    }
    private fun finishRealtimeStream(input: JSONObject, result: Result<String>) {
        val sequence = input.optLong("realtimeRequest")
        if (sequence != latestRealtimeRequest) return
        if (!isRealtimeStreamCurrent(input)) {
            cancelRealtimeStream()
            return
        }
        realtimeStreamId = null
        val answer = result.getOrNull()?.trim().orEmpty()
        val displayText = HintStreamPolicy.preview(answer.take(HintStreamPolicy.MAX_CHARS).trimEnd())
        if (displayText != null) {
            conversations.repository.applyDerived(RecordDerived(id = input.getString("derivedId"), recordId = input.getString("recordId"),
                sourceRevision = input.optInt("sourceRevision", 1), kind = "analysis", text = answer, agent = "phone-local-agent"))
            conversations.refreshRecords()
            dispatch(GlassesEvent.StandbyText(TranslationView("AI 힌트", displayText)))
        } else if (realtimePreviewRequest == sequence) {
            dispatch(GlassesEvent.StandbyText(TranslationView("AI 힌트", if (result.isFailure) "응답 미완료" else "듣는 중")))
        }
        realtimePreviewRequest = 0L; realtimePreviewRevision = -1L
    }
    private fun deliverExternalVoice(question: String, answer: String, input: JSONObject) {
        // DshRelay restores explicitly submitted non-voice tasks with an empty input object.
        // Preserve that existing notification path; it is not a passive voice hint.
        if (input.length() == 0) {
            if (answer.isNotBlank() && !destroyed && state.link.phase == LinkPhase.FULL && !state.disconnecting)
                connection.sendNotification("어시스턴트", answer)
            return
        }
        val source = ConversationSource.values().firstOrNull { it.name == input.optString("source") }
        val passiveHint = input.has("hintDeadline")
        if (!passiveHint && VoiceOutputPolicy.isSilent(answer, source)) return
        val id = input.optString("recordId")
        if (passiveHint) {
            val active = conversations.state
            if (source != ConversationSource.ALWAYS_ON || !active.running || active.stopping || active.activeRecord?.id != id ||
                active.source != ConversationSource.ALWAYS_ON || destroyed || state.disconnecting || state.link.phase != LinkPhase.FULL ||
                input.optString("serviceInstance") != serviceInstance || input.optString("contextKey") != nativeIdentity().getString("conversationKey") ||
                input.optBoolean("backfill") || voiceTransport.nativeActive || voiceTransport.source != ConversationSource.ALWAYS_ON ||
                state.intent != DisplayIntent.Idle || state.ownership != DisplayOwnership.APP) return
            if (!proactiveHintGate.accept(id, input.optLong("hintSequence"), answer, SystemClock.elapsedRealtime(), input.optLong("hintDeadline"))) return
        }
        if (id.isNotBlank()) {
            conversations.repository.applyDerived(RecordDerived(id = input.optString("derivedId").ifBlank { java.util.UUID.randomUUID().toString() }, recordId = id, sourceRevision = input.optInt("sourceRevision", 1),
                kind = "analysis", text = answer, agent = "phone-local-agent"))
            conversations.refreshRecords()
        }
        val wake = input.optString("wakeId").takeUnless { it.isBlank() || it == "null" }
        val active = conversations.state
        val sameRecord = id.isNotBlank() && active.running && !active.stopping && active.activeRecord?.id == id
        val route = VoiceOutputPolicy.route(source,
            currentService = input.optString("serviceInstance") == serviceInstance,
            currentContext = input.optString("contextKey") == nativeIdentity().getString("conversationKey"),
            backfill = input.optBoolean("backfill"),
            linkReady = !destroyed && state.link.phase == LinkPhase.FULL && !state.disconnecting,
            nativeWakeCurrent = wake != null && wake == voiceTransport.wakeId && voiceTransport.nativeActive,
            realtimeCurrent = input.optLong("realtimeRequest") == latestRealtimeRequest &&
                input.optLong("displayRevision", -1) == state.revision && sameRecord &&
                active.source == ConversationSource.REALTIME && conversationOwnsDisplay && state.ownership == DisplayOwnership.APP,
            alwaysOnCurrent = sameRecord && active.source == ConversationSource.ALWAYS_ON)
        if (route == VoiceOutputPolicy.Route.NONE) return
        if (!passiveHint) conversations.rememberReply(question, answer)
        when (route) {
            VoiceOutputPolicy.Route.NATIVE -> voiceTransport.reply(answer, question, checkNotNull(wake))
            VoiceOutputPolicy.Route.REALTIME -> {
                val ref = AnswerRef(AnswerSource.MOBILE_AGENT, input.getLong("realtimeRequest"))
                dispatch(GlassesEvent.AnswerRequested(ref))
                val permit = checkNotNull(state.answerPermit)
                autoClearFor = ref; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null
                dispatch(GlassesEvent.AnswerCompleted(AnswerContent(ref, question, answer), permit))
            }
            VoiceOutputPolicy.Route.NOTIFICATION -> connection.sendNotification("어시스턴트", answer)
            VoiceOutputPolicy.Route.NONE -> Unit
        }
    }
    fun pausePrompt() { stopConversation(); dispatch(GlassesEvent.PauseDisplay) }
    fun startTranslation() {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        val config = settings.read()
        translator.validate(config)
        cancelAlwaysOnRecovery()
        conversationOwnsDisplay = false
        if (conversations.state.running) conversations.stop()
        translator.start(config)
        dispatch(GlassesEvent.StartTranslation)
    }
    fun stopTranslation() { translator.stop(); if (state.intent == DisplayIntent.Translation) dispatch(GlassesEvent.PauseDisplay) }
    fun openStandby() { cancelAlwaysOnRecovery(); dispatch(GlassesEvent.OpenStandby) }
    /** One-tap realtime hint: open the window and start the realtime dual-mic record that actually processes speech. */
    fun startRealtimeHint() {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        if (conversations.state.running && conversations.state.source == ConversationSource.REALTIME) {
            conversationOwnsDisplay = true; dispatch(GlassesEvent.OpenStandby); return
        }
        check(!conversations.state.running) { "다른 기록(${conversations.state.source.title})이 실행 중입니다. 기록 탭에서 먼저 중지하세요" }
        translator.stop()
        startConversation(ConversationMode.DAILY, ConversationSource.REALTIME)
    }
    fun retryAudio() {
        check(state.link.phase == LinkPhase.FULL && state.session?.phase in setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY)) {
            "먼저 내비게이션 또는 실시간 힌트를 시작하고 안경의 확인을 기다리세요"
        }
        dispatch(GlassesEvent.RetryAudio)
    }
    fun dismissAnswer() = dispatch(GlassesEvent.DismissAnswer)

    fun submitAnswer(source: AnswerSource, text: String, context: String = "", allowTools: Boolean = true, retainHistory: Boolean = true, forceDsh: Boolean = false) {
        if (source == AnswerSource.MOBILE_AGENT) {
            check(agentServer.state.enabled) { "먼저 설정의 'DSH Go 및 외부 Agent'에서 로컬 엔드포인트를 활성화하고 DSH Go 플러그인을 시작하세요" }
        }
        when (source) {
            AnswerSource.MOBILE_AGENT -> check(!agent.running) { "답변을 기다리거나 먼저 중지하세요" }
            AnswerSource.COMPUTER_CODEX -> check(codex.connected && !codex.running) { "컴퓨터를 연결하고 현재 작업이 끝날 때까지 기다리세요" }
        }
        autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null
        val ref = AnswerRef(source, ++nextAnswer)
        dispatch(GlassesEvent.AnswerRequested(ref))
        pendingAnswers[source] = checkNotNull(state.answerPermit)
        if (source == AnswerSource.MOBILE_AGENT) voiceReply = null
        try {
            when (source) {
                AnswerSource.MOBILE_AGENT -> agent.submit(text, context, allowTools, retainHistory, forceDsh)
                AnswerSource.COMPUTER_CODEX -> codex.submit(text)
            }
        } catch (error: Exception) {
            pendingAnswers.remove(source); dispatch(GlassesEvent.CancelAnswer(source)); throw error
        }
    }
    fun cancelAnswer(source: AnswerSource) {
        invalidateAnswer(source)
        when (source) {
            AnswerSource.MOBILE_AGENT -> agent.cancel()
            AnswerSource.COMPUTER_CODEX -> codex.cancel()
        }
    }
    private fun invalidateAnswer(source: AnswerSource) {
        pendingAnswers.remove(source)
        dispatch(GlassesEvent.CancelAnswer(source))
    }
    private fun nativeIdentity(reset: Boolean = false): JSONObject {
        val config = settings.read()
        var changed = false
        if (config.optString("phoneInstanceId").isBlank()) { config.put("phoneInstanceId", java.util.UUID.randomUUID().toString()); changed = true }
        if (reset || config.optString("nativeConversationKey").isBlank()) { config.put("nativeConversationKey", java.util.UUID.randomUUID().toString()); changed = true }
        if (changed) settings.save(config)
        return JSONObject().put("phoneId", config.getString("phoneInstanceId")).put("conversationKey", config.getString("nativeConversationKey"))
    }
    fun newMobileConversation() {
        val previous = nativeIdentity().getString("conversationKey")
        nativeIdentity(reset = true)
        conversations.resetAgentConversation()
        cancelRealtimeStream()
        agent.harness.cancel("hint")
        agent.harness.cancelConversation(previous)
        clearAgent()
    }
    fun clearAgent() { invalidateAnswer(AnswerSource.MOBILE_AGENT); agent.clear() }
    fun connectCodex() { invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.connect() }
    fun closeCodex() { invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.close() }
    fun newCodexConversation() {
        check(!codex.running) { "먼저 현재 컴퓨터 작업을 종료하세요" }
        invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.newConversation()
    }
    private fun completed(source: AnswerSource, question: String, text: String) {
        val permit = pendingAnswers.remove(source) ?: return
        val delivery = voiceReply?.takeIf { it.ref == permit.ref }
        if (text.isBlank()) {
            if (delivery != null) voiceReply = null
            if (state.answerPermit == permit) dispatch(GlassesEvent.CancelAnswer(source))
            return
        }
        if (delivery != null) {
            voiceReply = null
            conversations.rememberReply(question, text)
            if (delivery.source == ConversationSource.NATIVE) {
                if (voiceTransport.nativeActive && delivery.wakeId == voiceTransport.wakeId && delivery.wakeId != null)
                    voiceTransport.reply(text, question, delivery.wakeId)
                else if (state.link.phase == LinkPhase.FULL) connection.sendNotification("어시스턴트", text)
                return
            }
            if (delivery.source == ConversationSource.ALWAYS_ON || state.answerPermit != permit) {
                if (state.link.phase == LinkPhase.FULL) connection.sendNotification("어시스턴트", text)
                return
            }
        }
        if (text.isNotBlank()) {
            autoClearFor = permit.ref; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null
            dispatch(GlassesEvent.AnswerCompleted(AnswerContent(permit.ref, question, text), permit))
        }
    }
    private fun updateAnswerClearTimer() {
        val ref = autoClearFor ?: return
        if ((state.intent as? DisplayIntent.Answer)?.ref != ref || state.ownership != DisplayOwnership.APP || state.link.phase != LinkPhase.FULL) {
            autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null; return
        }
        val session = state.session ?: return
        if (clearAnswerTask != null || session.phase != PagePhase.READY || session.writtenContent != session.content) return
        val seconds = settings.read().optInt("answerClearSeconds", 0).coerceIn(0,3600)
        if (seconds == 0) { autoClearFor = null; return }
        val revision = state.revision; val id = session.id
        clearAnswerTask = Runnable {
            if (state.session?.id == id && state.revision == revision && (state.intent as? DisplayIntent.Answer)?.ref == ref) clearDisplay()
        }.also { main.postDelayed(it, seconds * 1000L) }
    }
    fun revealAnswer(source: AnswerSource) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        val (question, answer) = when (source) {
            AnswerSource.MOBILE_AGENT -> agent.question to agent.answer
            AnswerSource.COMPUTER_CODEX -> codex.question to codex.answer
        }
        require(answer.isNotBlank()) { "표시할 답변이 아직 없습니다" }
        dispatch(GlassesEvent.RevealAnswer(AnswerContent(AnswerRef(source, ++nextAnswer), question, answer)))
    }
    private fun navigationSnapshot() = NavigationState(navigation.enabled, navigation.selectedKey, navigation.frame)
    private fun recordAPI(method: String, rawPath: String, body: JSONObject): JSONObject? {
        val path = rawPath.substringBefore('?')
        val query = if (rawPath.contains('?')) rawPath.substringAfter('?').split('&').associate { row ->
            java.net.URLDecoder.decode(row.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(row.substringAfter('=', ""), "UTF-8")
        } else emptyMap()
        val args = JSONObject(body.toString()).apply { query.forEach { (key, value) -> put(key, value) } }
        val repository = conversations.repository
        return when {
            method == "GET" && path in setOf("/v1/records", "/v1/conversations") || method == "POST" && path == "/v1/records/query" ->
                JSONObject().put("items", repository.query(args.optLong("from", Long.MIN_VALUE), args.optLong("to", Long.MAX_VALUE), args.optString("recordId"), args.optString("text")))
            method == "GET" && path.startsWith("/v1/conversations/") -> JSONObject().put("items", repository.query(id = path.substringAfterLast('/')))
            method == "GET" && path == "/v1/records/people" -> JSONObject().put("items", org.json.JSONArray(repository.people.map { it.json().apply { remove("samples") } }))
            method == "POST" && path == "/v1/records/derived" -> {
                val item = RecordDerived.parse(body)
                check(repository.applyDerived(item)) { "기록이 없습니다" }; conversations.refreshRecords()
                JSONObject().put("item", item.json()).put("persisted", true)
            }
            method == "GET" && path == "/v1/records/sync" -> JSONObject().put("enabled", recordSync.state.enabled).put("port", recordSync.state.port)
                .put("notice", recordSync.state.notice).put("addresses", org.json.JSONArray(recordSync.addresses()))
            else -> null
        }
    }
    private fun executeTool(name: String, args: JSONObject, completion: (Result<JSONObject>) -> Unit) {
        when (name) {
            "navigation_status" -> completion(Result.success(JSONObject().put("following", state.navigation.enabled)
                .put("notificationPresent", navigation.selected != null).put("status", navigation.status)))
            "stop_navigation_mirroring" -> { stopNavigation(); completion(Result.success(JSONObject().put("following", false).put("gaodeNavigationChanged", false))) }
            "show_notification" -> {
                connection.sendNotification(args.getString("title"), args.getString("body"))
                completion(Result.success(JSONObject().put("queued", true).put("visible", "unverified")))
            }
            "record_query" -> completion(Result.success(JSONObject().put("items", conversations.repository.query(args.optLong("from", Long.MIN_VALUE),
                args.optLong("to", Long.MAX_VALUE), args.optString("recordId"), args.optString("text")))))
            "record_annotate" -> {
                val item = RecordDerived.parse(args)
                check(conversations.repository.applyDerived(item)) { "기록이 없습니다" }; conversations.refreshRecords()
                completion(Result.success(JSONObject().put("item", item.json()).put("persisted", true)))
            }
            "memory_search" -> {
                val query = args.getString("query")
                val rows = conversations.repository.records.mapNotNull { record ->
                    val segments = record.segments.filter { it.text.contains(query, true) }
                    val memories = record.memories.filter { it.text.contains(query, true) || it.subject.contains(query, true) }
                    if (segments.isEmpty() && memories.isEmpty()) null else JSONObject().put("record_id", record.id).put("mode", record.mode.name)
                        .put("segments", org.json.JSONArray(segments.map { it.json() })).put("memories", org.json.JSONArray(memories.map { it.json() }))
                }
                completion(Result.success(JSONObject().put("items", org.json.JSONArray(rows))))
            }
            "teleprompter_start" -> startTeleprompter(args.getString("title"), args.getString("text"), args.optInt("speed", 2), completion = completion)
            "teleprompter_control" -> teleprompter.control(args.getString("action"), completion)
            "display_clear" -> { check(state.session?.mode == PageMode.PROMPT && state.ownership == DisplayOwnership.APP) { "이 앱이 소유한 실시간 힌트 페이지가 없습니다" }; clearDisplay(); completion(Result.success(JSONObject().put("queued", true))) }
            "device_read" -> requests.perform({ deviceSettings.readForTool() }, completion)
            else -> if (name.startsWith("device_")) deviceSettings.tool(name, args, completion)
                else content.execute(name, args, completion)
        }
    }
    private fun onLog(text: String) {
        android.util.Log.i("OpenRayneo", text)
        logs.addLast("${LocalTime.now().withNano(0)} $text")
        while (logs.size > 300) logs.removeFirst()
        if (!dispatching) notifyUI()
    }
    private fun notifyUI() { if (initialized && !destroyed) observers.toList().forEach { it() } }
    private fun summary() = state.displayDescription + (if (reconnectCapture.pending) " · 종일 기록 재개 대기" else "") + when (state.audio.phase) {
        AudioPhase.RECEIVING -> " · 음성 수신 중"
        AudioPhase.WAITING_PACKET, AudioPhase.WAITING_PCM -> " · 오디오 대기 중"
        AudioPhase.STALLED -> " · 오디오 중단됨"
        AudioPhase.FAILED -> " · 오디오 오류"
        AudioPhase.STOPPED -> ""
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenRayneo").setContentText(summary()).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "서비스 중지", stop).build()).build()
    }
    private fun updateNotification() {
        if (!initialized || !foreground || stopAfterDisconnect) return
        val text = summary()
        if (text != notificationText) { notificationText = text; getSystemService(NotificationManager::class.java).notify(1, notification()) }
    }
    override fun onDestroy() {
        reconnectCapture.cancel(); destroyed = true; observers.clear(); main.removeCallbacksAndMessages(null)
        navigation.observers.remove(navigationObserver)
        translator.close(); agent.close(); codex.close(); conversations.close(); agentServer.close(); recordSync.close(); requests.close(); content.close(); display.reset(); audio.close(); voiceAudio.close(); supervisor.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

internal const val DEFAULT_ANSWER_MAX_CHARS = 60
