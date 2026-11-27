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
internal data class DictationResult(val text: String, val notice: String? = null)

internal class PhoneDictation(private val context: Context, private val language: String, preferredEngine: String,
                             private val completed: (Result<DictationResult>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val config = JSONObject().put("asrEngine", when {
        preferredEngine == "stream" && language == "ko" && LocalModels.zipformer.installed(context) -> "stream"
        preferredEngine == "nemotron" && language in setOf("ko", "en") && LocalModels.nemotron.installed(context) -> "nemotron"
        preferredEngine == "local" && LocalModels.senseVoice.installed(context) -> "local"
        language == "ko" -> "stream"
        else -> "local"
    }).put("asrLanguage", language)
    private var lastPartial = "" // main only
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
    private fun complete(result: Result<DictationResult>) {
        main.post {
            if (!closed.compareAndSet(false, true)) return@post
            stopRequested = true
            round?.cancel()
            listening = false
            val draft = (finals + lastPartial).filter { it.isNotBlank() }.joinToString(" ")
            completed(if (result.isFailure && draft.isNotBlank()) Result.success(DictationResult(draft, "인식이 중단되어 지금까지의 내용을 입력했습니다. 전송 전에 확인해 주세요.")) else result)
        }
    }
    private fun render(partial: String = "") {
        lastPartial = partial
        preview = (finals + listOf(partial)).filter { it.isNotBlank() }.joinToString(" ")
    }
    @SuppressLint("MissingPermission")
    private fun capture() {
        var record: AudioRecord? = null
        // At most 30 seconds in RAM, only for a local retry if no final text arrives.
        val backup = FloatArray(16000 * 30)
        var captured = 0
        var peak = 0f
        val speech = runCatching { SpeechRounds.create(context, config,
            { text, _, _, _, _ -> main.post {
                if (!closed.get() && text.isNotBlank()) { finals.add(text); render() }
            } },
            { message -> complete(Result.failure(IllegalStateException(message))) },
            { main.post { if (!closed.get()) {
                if (finals.isNotEmpty()) {
                    backup.fill(0f)
                    complete(Result.success(DictationResult(finals.joinToString(" "))))
                } else retryLocal(backup, captured, peak)
            } } },
            { partial -> main.post { if (!closed.get()) render(partial) } }) }.getOrElse { complete(Result.failure(it)); return }
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
            val modelLabel = SpeechRounds.model(config)?.title.orEmpty()
            main.post { if (!closed.get() && !stopRequested) { listening = true; status = "듣는 중 · 최대 30초 · $modelLabel" } }
            val pcm = ShortArray(640)
            while (!closed.get() && !stopRequested && SystemClock.elapsedRealtime() - started < 30_000L) {
                val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "휴대폰 마이크 입력 중단 ($count)" }
                if (count == 0) { Thread.sleep(10); continue }
                check(speech.canAcceptAudio()) { "음성 인식 처리 지연으로 입력을 중지했습니다." }
                check(input.activeRecordingConfiguration?.isClientSilenced != true) { "Android에서 마이크 입력을 차단했습니다. 다른 앱의 녹음 또는 마이크 권한을 확인하세요." }
                val samples = FloatArray(count) { pcm[it] / 32768f }
                val copy = minOf(samples.size, backup.size - captured)
                if (copy > 0) { samples.copyInto(backup, captured, 0, copy); captured += copy }
                for (sample in samples) peak = maxOf(peak, kotlin.math.abs(sample))
                speech.append(samples)
            }
        } catch (error: Exception) {
            speech.cancel()
            complete(Result.failure(error))
            return
        } finally {
            record?.let { input -> runCatching { input.stop() }; runCatching { input.release() } }
        }
        if (closed.get()) { backup.fill(0f); speech.cancel(); return }
        main.post { if (!closed.get()) finish() }
        speech.finish()
        main.postDelayed({ if (!closed.get()) complete(Result.failure(IllegalStateException("음성 인식 시간이 초과되었습니다."))) }, 30_000L)
    }

    /** No VAD/denoising on retry; user still edits and explicitly sends the text. */
    private fun retryLocal(audio: FloatArray, count: Int, peak: Float) {
        val partial = lastPartial
        if (count < 1600 || peak == 0f || !LocalModels.senseVoice.installed(context)) {
            audio.fill(0f)
            complete(Result.success(DictationResult(partial, if (partial.isNotBlank()) "중간 인식 결과입니다. 확인 후 전송하세요." else "마이크에서 인식할 음성이 확인되지 않았습니다.")))
            return
        }
        status = "내장 SenseVoice로 다시 인식 중"
        LocalModels.executor.execute {
            val retry = runCatching {
                check(!closed.get())
                val model = LocalModels.senseVoice.acquire(context)
                try {
                    val stream = model.createStream()
                    try {
                        stream.acceptWaveform(audio.copyOf(count), 16000)
                        model.decode(stream)
                        val result = model.getResult(stream)
                        val text = result.text.trim()
                        val lang = result.lang.removePrefix("<|").removeSuffix("|>")
                        text.takeIf { it.isNotBlank() && (lang.isBlank() || lang == language || (language == "ko" && it.any { c -> c in '가'..'힣' })) }.orEmpty()
                    } finally { stream.release() }
                } finally { LocalModels.senseVoice.releaseUser() }
            }
            audio.fill(0f)
            val text = retry.getOrDefault("").ifBlank { partial }
            complete(Result.success(DictationResult(text, if (text.isBlank()) "음성 인식 결과가 없습니다. 마이크 경로와 음성 모델을 확인하세요." else "내장 모델 재인식 결과입니다. 확인 후 전송하세요.")))
        }
    }

}
