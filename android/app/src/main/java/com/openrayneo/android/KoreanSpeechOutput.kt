// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Explicit playback only; never select a network voice or persist spoken text. Main-thread owner. */
internal class KoreanSpeechOutput(private val context: Context, private val main: Handler, private val changed: () -> Unit) {
    var status = "음성 출력 대기"; private set
    var speaking = false; private set
    private var engine: TextToSpeech? = null
    private var generation = 0
    private var closed = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var guard = false
    private var receiverRegistered = false
    private var callbackRegistered = false
    private fun safeRoute(): Boolean = runCatching {
        val routes = audio.getAudioDevicesForAttributes(attributes)
        routes.isNotEmpty() && routes.all { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) }
    }.getOrDefault(false)
    private fun verifyRoute() {
        if (speaking && guard && !safeRoute()) fail("이어폰 출력 경로가 변경되어 음성을 중지했습니다")
    }
    private val routeWatch = object : Runnable {
        override fun run() {
            verifyRoute()
            if (speaking && guard) main.postDelayed(this, 250)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = verifyRoute()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = verifyRoute()
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && speaking && guard)
                fail("이어폰 연결이 해제되어 음성을 중지했습니다")
        }
    }
    fun speak(text: String, rate: Float = 1f, pitch: Float = 1f, headphonesOnly: Boolean = true) {
        check(!closed && text.isNotBlank())
        stop()
        guard = headphonesOnly
        if (guard && !safeRoute()) { fail("이어폰 미디어 출력을 확인하세요. 스피커로 자동 재생하지 않습니다"); return }
        val token = ++generation
        speaking = true; status = "한국어 오프라인 음성 준비 중"; changed()
        if (guard) {
            try {
                context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                audio.registerAudioDeviceCallback(deviceCallback, main); callbackRegistered = true
                main.post(routeWatch)
            } catch (_: Exception) { fail("이어폰 경로 감시를 시작하지 못했습니다"); return }
        }
        val spoken = text.take(2000)
        engine = TextToSpeech(context) { result -> main.post {
            if (closed || token != generation) return@post
            val tts = engine ?: return@post
            if (result != TextToSpeech.SUCCESS) { fail("휴대폰 TTS 엔진을 초기화하지 못했습니다"); return@post }
            val voice = runCatching { tts.voices.orEmpty().filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "KR" }.thenByDescending { it.quality }).firstOrNull() }.getOrNull()
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                fail("설치된 오프라인 한국어 음성이 없습니다. 휴대폰 TTS 설정에서 한국어 음성을 설치하세요."); return@post
            }
            if (tts.setSpeechRate(rate.coerceIn(0.75f, 1.5f)) != TextToSpeech.SUCCESS ||
                tts.setPitch(pitch.coerceIn(0.75f, 1.25f)) != TextToSpeech.SUCCESS) {
                fail("음성 속도 또는 높이를 적용하지 못했습니다"); return@post
            }
            if (guard && !safeRoute()) { fail("이어폰 출력이 확인되지 않아 재생하지 않습니다"); return@post }
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { if (token == generation) { status = "한국어 음성 재생 중"; changed() } } }
                override fun onDone(id: String?) { main.post { if (token == generation) { stop(); status = "음성 재생 완료"; changed() } } }
                override fun onError(id: String?) { main.post { if (token == generation) fail("한국어 음성 재생 실패") } }
            })
            if (guard && !safeRoute()) { fail("이어폰 출력 경로가 변경되었습니다"); return@post }
            if (tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "ko-$token") != TextToSpeech.SUCCESS) fail("한국어 음성 재생 실패")
        } }
        main.postDelayed({ if (token == generation && speaking) fail("음성 출력 제한 시간 초과") }, 180_000)
    }
    private fun fail(message: String) { stop(); status = message; changed() }
    fun stop() {
        generation++
        main.removeCallbacks(routeWatch)
        guard = false
        if (callbackRegistered) { runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }; callbackRegistered = false }
        if (receiverRegistered) { runCatching { context.unregisterReceiver(noisy) }; receiverRegistered = false }
        engine?.stop(); engine?.shutdown(); engine = null
        speaking = false; status = "음성 출력 대기"; changed()
    }
    fun close() { closed = true; stop() }
}
