// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.ContactsContract
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.json.JSONObject

internal class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private var statusOpenRequest by mutableIntStateOf(0)
    private fun acceptStatusIntent(incoming: Intent) {
        if (incoming.action == ACTION_OPEN_STATUS) {
            // Navigation only. MainActivity is exported; never perform privileged actions here.
            setIntent(Intent(incoming).setAction(null))
            statusOpenRequest++
        }
    }
    private fun receiveExternalHudText(incoming: Intent) {
        if (!AppUpdate.supported || incoming.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        // Exported activity: never open URIs, attachments, tools or AI. Share is preview only.
        model.attempt {
            val raw = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
            require(raw != null && raw.length in 1..ExternalHudText.MAX_INPUT) {
                "공유할 일반 텍스트는 4000자 이하로 선택하거나 복사해 주세요."
            }
            val text = raw.toString()
            require(ExternalHudText.pages(text).isNotEmpty()) { "표시할 일반 텍스트가 없습니다." }
            check(model.externalHudIncoming == null) { "이전에 공유한 텍스트를 먼저 확인하세요." }
            model.externalHudIncoming = text
            model.externalHudOpen = true
        }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptStatusIntent(intent)
        receiveExternalHudText(intent)
    }
    private var exportRecord: String? = null
    private val exportAudio = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportRecord; exportRecord = null
        if (uri != null && id != null) model.exportRecording(id, uri)
    }
    private var dictation by mutableStateOf<PhoneDictation?>(null)
    private var pendingVoice: AnswerSource? = null
    private var visible = false
    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            pendingVoice = null
            model.notice = "휴대폰 음성 입력에는 마이크 권한이 필요합니다."
        } else if (visible) startPendingVoice()
    }
    private fun voiceBusy(): Boolean {
        val s = model.state
        return s.conversation.running || s.conversation.stopping || s.alwaysOnResumePending ||
            s.nativeWakeAvailable || s.wakeActive || s.wakePending || s.translationActive ||
            s.captionActive || s.mediaCaption.busy || s.mediaTranslation.preparing || s.speechPlaying ||
            s.teleprompter.smartFollow || s.migration.busy
    }
    private fun requestVoice(source: AnswerSource) = model.attempt {
        check(dictation == null && pendingVoice == null && pendingMediaCaption == null) { "다른 음성 요청이 진행 중입니다." }
        check(!voiceBusy()) { "다른 음성 기능을 종료한 뒤 입력하세요." }
        pendingVoice = source
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            startPendingVoice()
        else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun startPendingVoice() {
        if (!visible || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val source = pendingVoice ?: return
        pendingVoice = null
        model.attempt {
            check(!voiceBusy() && dictation == null && pendingMediaCaption == null) { "다른 음성 기능이 시작되었습니다." }
            val language = KoreanLanguage.phoneInput(model.config.optString("phoneSpeechLanguage")).substringBefore('-')
            lateinit var session: PhoneDictation
            session = PhoneDictation(applicationContext, language) { result ->
                if (dictation !== session) return@PhoneDictation
                dictation = null
                result.fold({ text ->
                    if (text.isBlank()) model.notice = "인식된 음성이 없습니다."
                    else if (source == AnswerSource.MOBILE_AGENT)
                        model.phoneDraft = listOf(model.phoneDraft, text).filter { it.isNotBlank() }.joinToString(" ")
                    else model.computerDraft = listOf(model.computerDraft, text).filter { it.isNotBlank() }.joinToString(" ")
                }, { error -> model.error = error.message ?: "휴대폰 음성 입력 실패" })
            }
            dictation = session
            session.start()
        }
    }
    private fun cancelVoice() {
        val session = dictation
        dictation = null
        session?.cancel()
    }
    private val contact = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) model.attempt {
            contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) model.selectedContact = SelectedContact(cursor.getString(0), uri.toString())
            }
        }
    }
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.refreshPermissions()
            if (model.wantsScan) {
                model.wantsScan = false
                if (model.bluetoothAllowed) model.scan()
                else model.error = "안경 연결에는 근처 기기 권한이 필요합니다. 나중에 설정에서 허용할 수 있으며 다른 설정은 계속 사용할 수 있습니다."
            }
        }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneLocation.allowed(this)) model.attempt { model.service().enableLocationWhileVisible() }
    }
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                model.attempt {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readNBytes(16385) }
                    require(bytes.size <= 16384) { "설정 파일이 너무 큼" }
                    model.importConfig(JSONObject(String(bytes, Charsets.UTF_8)))
                }
        }
    private var pendingMediaCaption: MediaCaptionOptions? = null
    private val mediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val options = pendingMediaCaption
        pendingMediaCaption = null
        val data = result.data
        if (options != null && result.resultCode == RESULT_OK && data != null)
            model.attempt { model.service().startMediaCaption(options, result.resultCode, data) }
        else if (result.resultCode == RESULT_OK) model.error = "미디어 자막 요청이 변경되었습니다. 다시 시작하세요."
        else model.notice = "미디어 캡처 승인이 취소되었습니다."
    }
    private val mediaRecordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingMediaCaption != null) launchMediaConsent()
        else { pendingMediaCaption = null; model.error = "내부 재생음 수집에는 Android 오디오 권한이 필요합니다." }
    }
    private fun launchMediaConsent() {
        try {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            mediaProjection.launch(manager.createScreenCaptureIntent())
        } catch (failure: Exception) { pendingMediaCaption = null; model.error = failure.message ?: "캡처 승인 화면을 열지 못했습니다." }
    }
    private fun requestMediaCaption(options: MediaCaptionOptions) = model.attempt {
        check(pendingMediaCaption == null) { "시스템 캡처 승인 대기 중입니다." }
        model.service().checkMediaCaption(options)
        pendingMediaCaption = options
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchMediaConsent()
            else mediaRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
        } catch (failure: Exception) { pendingMediaCaption = null; throw failure }
    }

    private var naverAssociationPending = false
    private val naverAssociationConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            naverAssociationPending = false
            model.notice = "안경 부속 기기 등록이 취소되었습니다."
        }
    }
    private fun registerNaverCompanion() = model.attempt {
        check(visible && !naverAssociationPending) { "등록 화면을 확인하세요." }
        val address = checkNotNull(model.service().authenticatedNavigationDevice) { "먼저 안경을 완전히 연결하세요." }
        val manager = checkNotNull(getSystemService(android.companion.CompanionDeviceManager::class.java)) { "이 휴대폰은 부속 기기 등록을 지원하지 않습니다." }
        val existing = NaverCompanionAccess.matching(this, address).firstOrNull()
        if (existing != null) {
            NaverCompanionAccess.enable(this, address, existing, false)
            model.notice = "현재 안경의 백그라운드 지도 실행 사용 설정 완료 · 실제 실행 확인 필요"
        } else {
            val request = android.companion.AssociationRequest.Builder()
                .addDeviceFilter(android.companion.BluetoothDeviceFilter.Builder().setAddress(address.uppercase(java.util.Locale.ROOT)).build())
                .setSingleDevice(true).build()
            naverAssociationPending = true
            try {
                manager.associate(request, mainExecutor, object : android.companion.CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: android.content.IntentSender) {
                        if (!visible || isDestroyed || !naverAssociationPending) { naverAssociationPending = false; return }
                        runCatching {
                            naverAssociationConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(intentSender).build())
                        }.onFailure { naverAssociationPending = false; model.notice = "시스템 등록 화면 실행 실패" }
                    }
                    override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                        val authorized = !isDestroyed && naverAssociationPending &&
                            runCatching { model.service().authenticatedNavigationDevice.equals(address, ignoreCase = true) }.getOrDefault(false)
                        naverAssociationPending = false
                        if (authorized && runCatching { NaverCompanionAccess.enable(this@MainActivity, address, associationInfo, true) }.isSuccess)
                            model.notice = "안경 부속 기기 등록 완료 · 백그라운드 지도 실행 시험 가능"
                        else {
                            runCatching { manager.disassociate(associationInfo.id) }
                            if (!isDestroyed) model.notice = "연결 상태가 변경되어 부속 기기 등록을 적용하지 않았습니다."
                        }
                    }
                    override fun onFailure(errorMessage: CharSequence?) {
                        naverAssociationPending = false
                        if (!isDestroyed) model.notice = "안경 부속 기기 등록 실패 · 기존 연결 및 지도 기능 유지"
                    }
                })
            } catch (error: Exception) {
                naverAssociationPending = false
                throw error
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptStatusIntent(intent)
        if (savedInstanceState == null) receiveExternalHudText(intent)
        enableEdgeToEdge()
        setContent {
            CompanionTheme(model.appearance) {
                CompanionApp(
                    model,
                    UiActions(
                        bluetoothPermission = {
                            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                        },
                        exportRecording = { id -> exportRecord = id; exportAudio.launch("OpenRayneo-meeting-$id.wav") },
                        contact = { contact.launch(null) },
                        locationPermission = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) },
                        scan = {
                            if (model.bluetoothAllowed) model.scan()
                            else {
                                model.wantsScan = true
                                permissions.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                    )
                                )
                            }
                        },
                        notificationAccess = {
                            model.attempt {
                                startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                )
                            }
                        },
                        notificationPermission = {
                            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                        appSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        },
                        openNaverMap = {
                            model.attempt {
                                startActivity(checkNotNull(packageManager.getLaunchIntentForPackage(NavigationFeed.PACKAGE)) {
                                    "네이버지도 앱을 휴대폰에 설치하세요."
                                })
                            }
                        },
                        registerNaverCompanion = ::registerNaverCompanion,
                        revokeNaverCompanion = {
                            model.attempt {
                                model.service().cancelVoiceNavigationRequest()
                                NaverCompanionAccess.disable(this)
                                model.notice = "백그라운드 지도 실행 사용 중지 · 기존 블루투스 연결은 유지됩니다."
                            }
                        },
                        importConfig = {
                            importFile.launch(arrayOf("application/json", "text/plain"))
                        },
                        mediaCaption = ::requestMediaCaption,
                        voice = ::requestVoice,
                    ),
                    statusOpenRequest = statusOpenRequest,
                    phoneInput = dictation?.let { session ->
                        if (session.listening) "마이크 입력 중" else session.status
                    },
                )
                val activeVoice = dictation
                val busy = voiceBusy()
                LaunchedEffect(activeVoice, busy) {
                    if (activeVoice != null && busy && dictation === activeVoice) {
                        cancelVoice()
                        model.notice = "다른 음성 기능이 시작되어 휴대폰 입력을 취소했습니다."
                    }
                }
                activeVoice?.let { session ->
                    AlertDialog(onDismissRequest = ::cancelVoice,
                        title = { Text("휴대폰 음성 입력") },
                        text = { Text(session.status + "\n\n" + session.preview.ifBlank { "인식 내용이 여기에 표시됩니다. AI로 자동 전송하지 않습니다." }) },
                        confirmButton = { TextButton(enabled = session.listening, onClick = session::finish) { Text("입력 완료") } },
                        dismissButton = { TextButton(onClick = ::cancelVoice) { Text("취소") } })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        model.setHostVisible(true)
        NaverVoiceLaunch.resumed(this)
        startPendingVoice()
        model.refreshPermissions()
        model.updateCheck.refresh(false)
    }

    override fun onPause() {
        visible = false
        model.setHostVisible(false)
        NaverVoiceLaunch.paused(this)
        cancelVoice()
        super.onPause()
    }
    override fun onDestroy() {
        pendingVoice = null
        cancelVoice()
        super.onDestroy()
    }
}

internal const val ACTION_OPEN_STATUS = "com.openrayneo.android.OPEN_SYSTEM_STATUS"

internal data class UiActions(
    val bluetoothPermission: () -> Unit,
    val exportRecording: (String) -> Unit,
    val contact: () -> Unit,
    val locationPermission: () -> Unit,
    val scan: () -> Unit,
    val notificationAccess: () -> Unit,
    val notificationPermission: () -> Unit,
    val appSettings: () -> Unit,
    val openNaverMap: () -> Unit,
    val registerNaverCompanion: () -> Unit,
    val revokeNaverCompanion: () -> Unit,
    val importConfig: () -> Unit,
    val voice: (AnswerSource) -> Unit,
    val mediaCaption: (MediaCaptionOptions) -> Unit,
)
