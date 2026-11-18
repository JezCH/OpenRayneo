// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One ASR round over 16 kHz mono PCM; timestamps are milliseconds from the first appended sample. */
internal interface SpeechRound {
    fun start()
    fun canAcceptAudio(): Boolean
    fun append(samples: FloatArray)
    fun finish()
    fun cancel()
}

/** Chooses the cloud (Bailian) or on-device (SenseVoice) engine from config key "asrEngine". */
internal object SpeechRounds {
    fun local(config: JSONObject) = config.optString("asrEngine") == "local"
    /** Whether the configured engine can run now (key entered, or model downloaded). */
    fun ready(context: Context, config: JSONObject) = if (local(config)) LocalSpeechModel.installed(context) else config.optString("asrKey").isNotBlank()
    fun missing(config: JSONObject) = if (local(config)) "먼저 음성 설정에서 기기 ASR 모델(SenseVoice)을 내려받으세요" else "먼저 음성 설정에서 Bailian ASR API Key를 입력하세요"
    fun create(context: Context, config: JSONObject,
               sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
               failed: (String) -> Unit, completed: () -> Unit, interim: ((String) -> Unit)? = null): SpeechRound =
        if (local(config)) LocalASR(context, config, sentence, failed, completed, interim)
        else ConversationASR(config, sentence, failed, completed, interim)
}

/** SenseVoice int8 (zh/en/ja/ko/yue), downloaded once into app storage; loaded while rounds use it, freed after a minute idle. */
internal object LocalSpeechModel {
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    // Model first, so progress tracks the large file.
    private val files = listOf("model.int8.onnx" to 0.99f, "tokens.txt" to 0.01f)
    var downloading by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var message by mutableStateOf<String?>(null); private set
    /** All recognizer work (load, decode, release) is serialized here. */
    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "local-asr").apply { isDaemon = true } }
    private var recognizer: OfflineRecognizer? = null
    private var users = 0
    private var idle: ScheduledFuture<*>? = null

    private fun folder(context: Context) = File(context.filesDir, "asr/sense-voice-2024-07-17")
    fun installed(context: Context) = files.all { (name, _) -> File(folder(context), name).length() > 0 }

    fun download(context: Context) {
        if (downloading) return
        downloading = true; progress = 0f; message = "내려받는 중…"
        Thread {
            try {
                val dir = folder(context).apply { mkdirs() }
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                var before = 0f
                files.forEach { (name, weight) ->
                    val target = File(dir, name)
                    if (target.length() == 0L) {
                        val part = File(dir, "$name.part")
                        http.newCall(Request.Builder().url(BASE + name).build()).execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            val body = checkNotNull(response.body)
                            val total = body.contentLength()
                            var done = 0L; var shown = 0L
                            body.byteStream().use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    output.write(buffer, 0, count); done += count
                                    if (total > 0 && done - shown > 1_000_000) { shown = done; progress = before + weight * done / total }
                                }
                            } }
                            check(total <= 0 || done == total) { "파일이 덜 받아졌습니다: $name" }
                        }
                        check(part.renameTo(target)) { "파일 저장 실패: $name" }
                    }
                    before += weight; progress = before
                }
                progress = 1f; message = "기기 ASR 모델 준비됨"
            } catch (error: Exception) {
                message = "모델 내려받기 실패: ${error.message ?: error.javaClass.simpleName}"
            } finally { downloading = false }
        }.apply { isDaemon = true }.start()
    }

    /** Executor thread only. A round calls this once when it starts using the model. */
    fun acquire(context: Context): OfflineRecognizer {
        idle?.cancel(false); idle = null
        val loaded = recognizer ?: load(context)
        users++
        return loaded
    }
    /** Executor thread only. The last user leaving schedules the release. */
    fun releaseUser() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && recognizer != null) {
            idle?.cancel(false)
            idle = executor.schedule(Runnable { if (users == 0) { recognizer?.release(); recognizer = null }; idle = null }, 60, TimeUnit.SECONDS)
        }
    }
    private fun load(context: Context): OfflineRecognizer {
        check(installed(context)) { "기기 ASR 모델이 없습니다. 음성 설정에서 내려받으세요" }
        val dir = folder(context)
        // Always automatic language detection; rounds filter results by their language hints.
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(File(dir, "model.int8.onnx").absolutePath, "", useInverseTextNormalization = true),
                tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2))
        return OfflineRecognizer(null, config).also { recognizer = it }
    }
}

/**
 * On-device round: silero VAD cuts sentences, SenseVoice decodes each one (non-streaming).
 * With [interim] set, the unfinished utterance is re-decoded about once a second for live captions.
 * Callbacks run on [LocalSpeechModel.executor]; callers post onto their own threads as with the cloud round.
 */
internal class LocalASR(private val context: Context, config: JSONObject,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit,
    private val interim: ((String) -> Unit)? = null) : SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val allowed = config.optString("asrLanguage").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val executor = LocalSpeechModel.executor
    private val queued = AtomicInteger()
    @Volatile private var ended = false
    @Volatile private var finishing = false
    // Executor-thread state.
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private val window = FloatArray(WINDOW)
    private var windowSize = 0
    private var index = 0
    private val recent = ArrayDeque<FloatArray>()
    private var live = FloatArray(MAX_LIVE)
    private var liveSize = 0
    private var liveDecodedAt = 0

    override fun start() { executor.execute { if (!ended) attempt { prepare() } } }
    override fun canAcceptAudio() = !ended && !finishing && queued.get() < MAX_QUEUED
    override fun append(samples: FloatArray) {
        if (ended || finishing) return
        // Same contract as the cloud round: a backlog fails the round so the caller pauses and backfills later.
        check(queued.get() + samples.size <= MAX_BACKLOG) { "기기 ASR 대기 오디오가 15초를 넘어 이번 라운드를 중지했습니다" }
        queued.addAndGet(samples.size)
        executor.execute {
            queued.addAndGet(-samples.size)
            if (!ended) attempt { feed(samples) }
        }
    }
    override fun finish() {
        if (ended || finishing) return
        finishing = true
        executor.execute {
            if (ended) return@execute
            attempt {
                val detector = prepare()
                if (windowSize > 0) { detector.acceptWaveform(window.copyOf(windowSize)); windowSize = 0 }
                detector.flush(); drain(detector)
                ended = true; release(); completed()
            }
        }
    }
    override fun cancel() { if (!ended) { ended = true; executor.execute { release() } } }

    private fun attempt(work: () -> Unit) {
        try { work() } catch (error: Exception) {
            if (!ended) { ended = true; release(); failed("기기 ASR 실패: ${error.message ?: error.javaClass.simpleName}") }
        }
    }
    private fun prepare(): Vad {
        vad?.let { return it }
        if (recognizer == null) recognizer = LocalSpeechModel.acquire(context)
        return Vad(context.assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig("voice-models/silero-vad.onnx", minSilenceDuration = 0.4f, maxSpeechDuration = 12f),
            sampleRate = 16000, numThreads = 1)).also { vad = it }
    }
    private fun release() {
        vad?.release(); vad = null
        if (recognizer != null) { recognizer = null; LocalSpeechModel.releaseUser() }
    }

    private fun feed(samples: FloatArray) {
        val detector = prepare()
        var first = 0
        while (first < samples.size) {
            val count = minOf(WINDOW - windowSize, samples.size - first)
            samples.copyInto(window, windowSize, first, first + count)
            windowSize += count; first += count
            if (windowSize == WINDOW) {
                val chunk = window.copyOf()
                detector.acceptWaveform(chunk); windowSize = 0
                if (interim != null) track(detector, chunk)
                drain(detector)
            }
        }
    }

    /** Keeps the audio of the utterance in progress (with a short lead-in) and decodes it periodically. */
    private fun track(detector: Vad, chunk: FloatArray) {
        if (detector.isSpeechDetected()) {
            if (liveSize == 0) { recent.forEach(::keep); liveDecodedAt = 0 }
            keep(chunk)
            // Captions are best effort: skip while audio is waiting, so finals never fall behind.
            if (liveSize - liveDecodedAt >= INTERIM_STEP && queued.get() < INTERIM_STEP) {
                liveDecodedAt = liveSize
                decode(live.copyOfRange(maxOf(0, liveSize - MAX_INTERIM), liveSize))?.let { (text, _) -> interim?.invoke(text) }
            }
        } else liveSize = 0
        recent.addLast(chunk); while (recent.size > LEAD_WINDOWS) recent.removeFirst()
    }

    /** Appends to the in-progress buffer; past [MAX_LIVE] the oldest half is dropped. */
    private fun keep(chunk: FloatArray) {
        if (liveSize + chunk.size > MAX_LIVE) {
            val half = MAX_LIVE / 2
            live.copyInto(live, 0, liveSize - half, liveSize); liveSize = half; liveDecodedAt = 0
        }
        chunk.copyInto(live, liveSize); liveSize += chunk.size
    }

    private fun drain(detector: Vad) {
        while (!detector.empty()) {
            val segment = detector.front(); detector.pop()
            liveSize = 0; liveDecodedAt = 0
            val (text, _) = decode(segment.samples) ?: continue
            val begin = segment.start.toLong() * 1000 / 16000
            sentence(text, begin, begin + segment.samples.size * 1000L / 16000, "$task:${index++}", emptyList())
        }
    }

    /** Returns null for empty results or a detected language outside the configured hints (noise is often read as zh). */
    private fun decode(samples: FloatArray): Pair<String, String>? {
        val recognizer = checkNotNull(recognizer)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            val lang = result.lang.removePrefix("<|").removeSuffix("|>")
            if (text.isEmpty() || allowed.isNotEmpty() && lang.isNotEmpty() && lang !in allowed) return null
            return text to lang
        } finally { stream.release() }
    }

    private companion object {
        const val WINDOW = 512
        const val LEAD_WINDOWS = 8
        const val INTERIM_STEP = 16000
        const val MAX_LIVE = 16000 * 12
        const val MAX_QUEUED = 16000 * 8
        const val MAX_BACKLOG = 16000 * 15
        const val MAX_INTERIM = 16000 * 6
    }
}
