// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
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
    fun speak(text: String) {
        check(!closed && text.isNotBlank())
        stop()
        val token = ++generation
        speaking = true; status = "한국어 오프라인 음성 준비 중"; changed()
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
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { if (token == generation) { status = "한국어 음성 재생 중"; changed() } } }
                override fun onDone(id: String?) { main.post { if (token == generation) { stop(); status = "음성 재생 완료"; changed() } } }
                override fun onError(id: String?) { main.post { if (token == generation) fail("한국어 음성 재생 실패") } }
            })
            if (tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "ko-$token") != TextToSpeech.SUCCESS) fail("한국어 음성 재생 실패")
        } }
        main.postDelayed({ if (token == generation && speaking) fail("음성 출력 제한 시간 초과") }, 180_000)
    }
    private fun fail(message: String) { stop(); status = message; changed() }
    fun stop() {
        generation++
        engine?.stop(); engine?.shutdown(); engine = null
        speaking = false; status = "음성 출력 대기"; changed()
    }
    fun close() { closed = true; stop() }
}
