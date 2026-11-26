// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Public Korean tiny Zipformer KWS (kairess/ko-kws-zipformer-tiny, Apache-2.0).
 * Uses the existing explicit model download and storage path. No audio goes to the model host. */
internal object KoreanKeywordModels {
    val model = LocalModel("한국어 경량 호출", "약 9.3MB", "asr/ko-kws-zipformer-tiny",
        "ko-kws-zipformer-tiny",
        "https://huggingface.co/kairess/ko-kws-zipformer-tiny/resolve/main/",
        listOf("encoder.int8.onnx" to 0.90f, "decoder.int8.onnx" to 0.045f,
            "joiner.int8.onnx" to 0.045f, "tokens.txt" to 0.005f, "bpe.model" to 0.005f),
        { dir -> dir }, {})

    fun spotter(context: Context, options: KoreanWakeOptions): KeywordSpotter {
        val dir = checkNotNull(model.location(context)) { "경량 호출 모델을 먼저 내려받으세요" }
        val modelFile = File(dir, "bpe.model")
        require(modelFile.length() in 1..1_000_000)
        val tokens = File(dir, "tokens.txt")
        require(tokens.length() in 1..1_000_000)
        val vocabulary = tokens.readLines().mapNotNull { line ->
            line.trim().substringBeforeLast(' ', "").takeIf { it.isNotEmpty() }
        }.toSet()
        val tokenizer = KoreanKeywordTokenizer(modelFile.readBytes(), vocabulary)
        val keywords = options.phrases.map { phrase ->
            tokenizer.encode(phrase).joinToString(" ") + " :1.0 #0.60 @" + phrase.filterNot(Char::isWhitespace)
        }
        val file = File.createTempFile("korean-wake-", ".txt", context.cacheDir)
        try {
            file.writeText(keywords.joinToString("\n", postfix = "\n"))
            return KeywordSpotter(null, KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath),
                    tokens = tokens.absolutePath, numThreads = 1, modelType = "zipformer2"),
                keywordsFile = file.absolutePath, keywordsScore = 1.0f, keywordsThreshold = 0.60f,
                maxActivePaths = 4, numTrailingBlanks = 2))
        } finally { file.delete() }
    }
}

/** Independent small-model idle detection. A detection is not authorization: start the installed
 * LOCAL ASR only for verification, replay a bounded in-RAM pre-roll, and require a literal wake
 * prefix before forwarding any sentence. A false candidate times out back to keyword detection.
 * All native calls and callbacks execute on the existing serialized local-ASR worker. */
internal class KoreanKeywordRound(private val context: Context, private val config: JSONObject,
    private val options: KoreanWakeOptions,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit,
    private val stage: (String) -> Unit) : SpeechRound {
    private val worker = LocalModels.executor
    private val queued = AtomicInteger()
    @Volatile private var ended = false
    private var detector: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var candidate: SpeechRound? = null
    private var epoch = 0L
    private var confirmed = false
    private var started = 0L
    private var deadline = 0L
    private val matcher = KoreanWakeMatcher(options)
    private val ring = ArrayDeque<FloatArray>()
    private var ringSamples = 0

    override fun start() {
        worker.execute { if (!ended) attempt {
            require(SpeechRounds.model(config) != null && SpeechRounds.ready(context, config)) { "확인용 로컬 음성 모델을 준비하세요" }
            detector = KoreanKeywordModels.spotter(context, options)
            openDetector()
        } }
    }
    override fun canAcceptAudio() = !ended && queued.get() < MAX_QUEUE
    override fun append(samples: FloatArray) {
        if (ended || samples.isEmpty()) return
        check(queued.addAndGet(samples.size) <= MAX_QUEUE * 2) {
            queued.addAndGet(-samples.size); "경량 호출 오디오 지연 초과"
        }
        worker.execute {
            queued.addAndGet(-samples.size)
            if (!ended) attempt { accept(samples) }
        }
    }
    override fun finish() = cancel() // Never send delayed recognition after the explicit session ends.
    override fun cancel() {
        if (ended) return
        ended = true
        worker.execute { release() }
    }

    private fun openDetector() {
        if (ended) return
        stream?.release()
        stream = checkNotNull(detector).createStream()
        ring.clear(); ringSamples = 0
        stage("경량 호출어 감지 대기")
    }
    private fun accept(samples: FloatArray) {
        val asr = candidate
        if (asr != null) {
            if (!confirmed && SystemClock.elapsedRealtime() > deadline) {
                reject(); return
            }
            if (!asr.canAcceptAudio()) { reject(); return }
            asr.append(samples)
            return
        }
        ring.addLast(samples); ringSamples += samples.size
        while (ringSamples > PRE_ROLL && ring.size > 1) ringSamples -= ring.removeFirst().size
        val current = checkNotNull(stream)
        val model = checkNotNull(detector)
        current.acceptWaveform(samples, 16000)
        while (model.isReady(current)) {
            model.decode(current)
            val keyword = model.getResult(current).keyword
            if (keyword.isNotBlank() && options.phrases.any { it.filterNot(Char::isWhitespace) == keyword.filterNot(Char::isWhitespace) }) {
                verify(); return
            }
        }
    }
    private fun verify() {
        val token = ++epoch
        confirmed = false
        started = SystemClock.elapsedRealtime()
        deadline = started + 8_000L
        stream?.release(); stream = null
        stage("호출 후보 · 로컬 음성 인식으로 확인 중")
        val round = SpeechRounds.create(context, config, { text, from, to, id, words ->
            if (!ended && epoch == token) {
                if (!confirmed) {
                    val called = matcher.match(text)
                    if (called == null || (called.isNotEmpty() && called.length !in 2..240)) {
                        // Keep listening within the short verification window. The candidate alone
                        // must never arm an external request or the existing follow-up window.
                    } else {
                        confirmed = true
                        stage("호출어 확인 · 질문 인식 중")
                        sentence(text, from, to, id, words)
                    }
                } else sentence(text, from, to, id, words)
            }
        }, { message -> if (!ended && token == epoch) attempt { reject(); stage("호출 확인 실패 · 다시 대기") } },
            { if (!ended && token == epoch) attempt { reject() } })
        candidate = round
        round.start()
        for (chunk in ring) round.append(chunk)
        ring.clear(); ringSamples = 0
    }
    private fun reject() {
        ++epoch
        candidate?.cancel(); candidate = null
        confirmed = false
        openDetector()
    }
    private fun attempt(block: () -> Unit) {
        try { block() } catch (error: Exception) {
            if (!ended) {
                ended = true
                release()
                failed("경량 호출 실패: " + (error.message ?: error.javaClass.simpleName))
            }
        } catch (error: LinkageError) {
            if (!ended) { ended = true; release(); failed("경량 호출 엔진을 이 앱 빌드에서 사용할 수 없습니다") }
        }
    }
    private fun release() {
        ++epoch
        candidate?.cancel(); candidate = null
        stream?.release(); stream = null
        detector?.release(); detector = null
        ring.clear(); ringSamples = 0
    }
    companion object {
        private const val PRE_ROLL = 16_000 * 4
        private const val MAX_QUEUE = 16_000 * 2
    }
}
