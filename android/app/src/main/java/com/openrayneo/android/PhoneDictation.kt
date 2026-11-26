// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, foreground-only dictation to an editable draft. No recording files,
 * cloud ASR, automatic sends or background microphone permission. */
internal class PhoneDictation(private val context: Context, language: String,
                             private val completed: (Result<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val config = JSONObject().put("asrEngine", if (language == "ko") "stream" else "local").put("asrLanguage", language)
    private val closed = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var round: SpeechRound? = null
    private val finals = ArrayList<String>() // main only
    var status by mutableStateOf("내장 음성 모델 준비 중"); private set
    var preview by mutableStateOf(""); private set
    var listening by mutableStateOf(false); private set

    fun start() {
        val source = checkNotNull(SpeechRounds.model(config))
        // Load before opening the microphone, so the first utterance cannot outrun model extraction.
        LocalModels.executor.execute {
            if (closed.get()) return@execute
            val ready = runCatching {
                source.acquire(context)
                source.releaseUser()
            }
            if (ready.isFailure) {
                complete(Result.failure(IllegalStateException("내장 음성 모델을 준비하지 못했습니다.", ready.exceptionOrNull())))
            } else if (!closed.get()) Thread(::capture, "openrayneo-phone-dictation").apply { isDaemon = true }.start()
        }
    }
    fun finish() {
        if (closed.get()) return
        stopRequested = true
        listening = false
        status = "마지막 문장 인식 중"
    }
    fun cancel() {
        if (!closed.compareAndSet(false, true)) return
        stopRequested = true
        round?.cancel()
    }
    private fun complete(result: Result<String>) {
        main.post {
            if (!closed.compareAndSet(false, true)) return@post
            stopRequested = true
            round?.cancel()
            listening = false
            completed(result)
        }
    }
    private fun render(partial: String = "") {
        preview = (finals + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ")
    }
    @SuppressLint("MissingPermission")
    private fun capture() {
        var record: AudioRecord? = null
        val speech = SpeechRounds.create(context, config,
            { text, _, _, _, _ -> main.post {
                if (!closed.get() && text.isNotBlank()) { finals.add(text); render() }
            } },
            { message -> complete(Result.failure(IllegalStateException(message))) },
            { main.post { if (!closed.get()) complete(Result.success(finals.joinToString(" "))) } },
            { partial -> main.post { if (!closed.get()) render(partial) } })
        round = speech
        try {
            if (closed.get()) { speech.cancel(); return }
            speech.start()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "휴대폰 마이크의 16kHz 입력을 사용할 수 없습니다." }
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 32000)).build()
            record = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "휴대폰 마이크 준비 실패" }
            if (closed.get()) return
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "휴대폰 마이크 시작 실패" }
            val started = SystemClock.elapsedRealtime()
            main.post { if (!closed.get() && !stopRequested) { listening = true; status = "듣는 중 · 최대 30초" } }
            val pcm = ShortArray(640)
            while (!closed.get() && !stopRequested && SystemClock.elapsedRealtime() - started < 30_000L) {
                val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "휴대폰 마이크 입력 중단 ($count)" }
                if (count == 0) { Thread.sleep(10); continue }
                check(speech.canAcceptAudio()) { "음성 인식 처리 지연으로 입력을 중지했습니다." }
                speech.append(FloatArray(count) { pcm[it] / 32768f })
            }
        } catch (error: Exception) {
            speech.cancel()
            complete(Result.failure(error))
            return
        } finally {
            record?.let { input -> runCatching { input.stop() }; runCatching { input.release() } }
        }
        if (closed.get()) { speech.cancel(); return }
        main.post { if (!closed.get()) finish() }
        speech.finish()
        main.postDelayed({ if (!closed.get()) complete(Result.failure(IllegalStateException("음성 인식 시간이 초과되었습니다."))) }, 30_000L)
    }
}
