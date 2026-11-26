// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.json.JSONObject

internal class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private var exportRecord: String? = null
    private val exportAudio = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportRecord; exportRecord = null
        if (uri != null && id != null) model.exportRecording(id, uri)
    }
    private var voiceSource = AnswerSource.MOBILE_AGENT
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

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (voiceSource == AnswerSource.MOBILE_AGENT) model.phoneDraft = text
                else model.computerDraft = text
            }
        }

    private fun receiveExternalHudText(incoming: Intent) {
        if (!AppUpdate.supported || incoming.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        // Treat every sender as untrusted. Only preview explicit text, never read attachments or execute it.
        model.attempt {
            val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            require(text.isNotBlank() && text.length <= ExternalHudText.MAX_INPUT) {
                "공유할 일반 텍스트는 4000자 이하로 선택하거나 복사해 주세요."
            }
            model.externalHudDraft = text
            model.externalHudOpen = true
        }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveExternalHudText(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveExternalHudText(intent)
        voiceSource =
            savedInstanceState?.getString("voiceSource")?.let(AnswerSource::valueOf)
                ?: AnswerSource.MOBILE_AGENT
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
                        openGaode = {
                            model.attempt {
                                startActivity(
                                    checkNotNull(
                                        packageManager.getLaunchIntentForPackage(
                                            NavigationFeed.PACKAGE
                                        )
                                    ) {
                                        "먼저 휴대폰에 Amap(가오더 지도)을 설치하세요"
                                    }
                                )
                            }
                        },
                        importConfig = {
                            importFile.launch(arrayOf("application/json", "text/plain"))
                        },
                        mediaCaption = ::requestMediaCaption,
                        voice = { source ->
                            model.attempt {
                                voiceSource = source
                                speech.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, KoreanLanguage.phoneInput(model.config.optString("phoneSpeechLanguage")))
                                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "AI에게 질문할 내용을 말하세요")
                                )
                            }
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("voiceSource", voiceSource.name)
        super.onSaveInstanceState(outState)
    }
}

internal data class UiActions(
    val bluetoothPermission: () -> Unit,
    val exportRecording: (String) -> Unit,
    val contact: () -> Unit,
    val locationPermission: () -> Unit,
    val scan: () -> Unit,
    val notificationAccess: () -> Unit,
    val notificationPermission: () -> Unit,
    val appSettings: () -> Unit,
    val openGaode: () -> Unit,
    val importConfig: () -> Unit,
    val voice: (AnswerSource) -> Unit,
    val mediaCaption: (MediaCaptionOptions) -> Unit,
)
