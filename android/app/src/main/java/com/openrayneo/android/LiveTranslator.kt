// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Korean ↔ English live interpretation on the phone. The glasses prompt page is only the window and
 * microphone: glasses PCM → Bailian ASR (ko,en hints, interim captions) → chat-completions translation.
 * Display: top line = latest speech (partial or final), bottom line = translation of the latest final
 * sentence ("…" while pending). Only the newest final is translated, so the glasses never fall behind.
 * All callbacks are funnelled onto [main]; nothing is stored or added to records.
 */
internal class LiveTranslator(
    private val context: android.content.Context,
    private val main: Handler,
    private val show: (source: String, target: String) -> Unit,
    private val changed: () -> Unit,
    /** The translator gave up on its own (e.g. repeated ASR failures); the owner should close the window. */
    private val gaveUp: () -> Unit,
) {
    @Volatile var active = false; private set
    @Volatile var status = OFF; private set
    @Volatile var captionOnly = false; private set
    var offlineMode = false; private set
    @Volatile var mediaSessionId: String? = null; private set
    private var wakeWindow: KoreanWakeWindow? = null
    private var wakeAction: ((String) -> Unit)? = null
    val wakeActive get() = active && wakeWindow != null
    val offline = OfflineTranslation(main, changed)
    val mediaTranslation = MediaLocalTranslation(context, main, changed)
    private var mediaTranslate = false
    private var mediaLanguage = "en"
    private var mediaFinalSource = ""
    private var mediaFinalTarget = ""
    private var mediaRenderedSequence = 0L

    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val translations = Executors.newSingleThreadExecutor()
    private var config = JSONObject()
    @Volatile private var generation = 0
    private var asr: SpeechRound? = null
    private var resampler = ConversationAudio()
    @Volatile private var call: Call? = null
    // Main-thread display state.
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var latestFinal = 0L
    private var sourceLine = ""
    private var targetLine = ""
    private var failures = 0
    private var restartTask: Runnable? = null

    /** Throws with a user-facing message when ASR or the translation model is not configured. */
    fun validate(settings: JSONObject, local: Boolean = false) {
        val speech = if (local) offlineSpeech(settings) else SpeechRounds.bilingual(context, settings)
        require(SpeechRounds.ready(context, speech)) { SpeechRounds.missing(speech) }
        if (local) { require(offline.readyFor(settings)) { "먼저 휴대폰 로컬 번역 모델 연결을 시험하세요" }; return }
        require(MobileModelEndpoint.isConfigured(settings.optString("modelUrl"), settings.optString("modelName"), settings.optString("modelKey"))) {
            "번역에는 기록 요약 모델(주소, 모델 이름, API Key)이 필요합니다. 음성 설정에서 먼저 입력하세요"
        }
        MobileModelEndpoint.resolve(settings.optString("modelUrl"), settings.optString("modelKey"))
    }

    private fun offlineSpeech(settings: JSONObject): JSONObject {
        val copy = JSONObject().put("asrEngine", SpeechRounds.engine(settings)).put("asrLanguage", "ko,en")
        if (SpeechRounds.engine(copy) !in setOf("local", "nemotron")) {
            copy.put("asrEngine", if (LocalModels.nemotron.installed(context)) "nemotron" else "local")
        }
        return copy
    }

    fun captionConfig(settings: JSONObject): JSONObject {
        val speech = JSONObject().put("asrEngine", SpeechRounds.engine(settings))
            .put("asrLanguage", SpeechRounds.languageHints(settings))
        require(SpeechRounds.model(speech) != null) { "자막은 기기 내장 음성 인식 모델만 지원합니다. 음성 설정에서 로컬 모델을 선택하세요." }
        require(SpeechRounds.ready(context, speech)) { SpeechRounds.missing(speech) }
        return speech
    }

    /** Must be called on the main thread. No cloud fallback in caption mode. */
    fun start(settings: JSONObject, captions: Boolean = false, local: Boolean = false) {
        val speech = if (captions) captionConfig(settings) else {
            validate(settings, local)
            if (local) offlineSpeech(settings) else JSONObject(SpeechRounds.bilingual(context, settings).toString()).put("asrLanguage", "ko,en")
        }
        startSpeech(speech, captions, local)
    }

    /** Reuse the installed ASR; never change the user's everyday ASR settings or fall back to cloud. */
    fun mediaCaptionConfig(language: String): JSONObject {
        require(language in setOf("en", "ja", "zh", "ko"))
        val engine = when {
            language == "ko" && LocalModels.zipformer.installed(context) -> "stream"
            language in setOf("ko", "en") && LocalModels.nemotron.installed(context) -> "nemotron"
            else -> "local" // SenseVoice supports English, Japanese, Chinese and Korean.
        }
        val speech = JSONObject().put("asrEngine", engine).put("asrLanguage", language)
        require(SpeechRounds.ready(context, speech)) { SpeechRounds.missing(speech) }
        return speech
    }
    fun startMediaCaption(id: String, options: MediaCaptionOptions) = startSpeech(mediaCaptionConfig(options.language), true, false, id,
        MediaTranslationRoute.required(options.language, options.translateToKorean), options.language)

    private fun startSpeech(speech: JSONObject, captions: Boolean, local: Boolean, mediaId: String? = null,
                            translateMedia: Boolean = false, mediaSourceLanguage: String = "en") {
        stop()
        mediaTranslate = translateMedia
        mediaLanguage = mediaSourceLanguage
        mediaFinalSource = ""; mediaFinalTarget = ""; mediaRenderedSequence = 0
        synchronized(this) {
            mediaSessionId = mediaId
            captionOnly = captions
            offlineMode = local && !captions
            config = speech
            generation++
            active = true
            resampler = ConversationAudio()
        }
        history.clear(); latestFinal = 0; sourceLine = ""; targetLine = ""; failures = 0
        if (mediaTranslate) mediaTranslation.begin(checkNotNull(mediaId), mediaLanguage)
        status = listening()
        openASR()
        changed()
    }

    fun startWake(settings: JSONObject, action: (String) -> Unit) {
        val korean = JSONObject(settings.toString()).put("asrLanguage", "ko")
        val options = KoreanWakeOptions.from(settings.optString("wakePhrases"), settings.optString("wakeSeconds"), settings.optString("wakeFollowSeconds"))
        start(korean, captions = true)
        wakeWindow = KoreanWakeWindow(android.os.SystemClock.elapsedRealtime(), options)
        wakeAction = action
        status = "한국어 호출 대기 · " + options.phrases.joinToString(" / ")
        show("한국어 호출 대기", options.phrases.first() + ", 질문하세요")
        changed()
    }
    fun pollWake(now: Long) {
        if (wakeWindow?.expired(now) == true) { stop("한국어 호출 대기 종료"); gaveUp() }
    }

    /** Must be called on the main thread. [reason] stays visible as the status after stopping. */
    fun stop(reason: String? = null) {
        val wasActive: Boolean
        val oldMedia = mediaSessionId
        synchronized(this) {
            wasActive = active
            active = false
            mediaSessionId = null
            generation++
            asr?.cancel(); asr = null
        }
        restartTask?.let(main::removeCallbacks); restartTask = null
        call?.cancel()
        offline.cancel()
        if (oldMedia != null) mediaTranslation.end(oldMedia)
        mediaTranslate = false; mediaFinalSource = ""; mediaFinalTarget = ""; mediaRenderedSequence = 0
        wakeWindow = null; wakeAction = null
        history.clear(); sourceLine = ""; targetLine = ""
        if (wasActive || reason != null) { status = reason ?: if (captionOnly) "실시간 자막 꺼짐" else OFF; changed() }
    }

    /** Called on the audio worker with 48 kHz PCM from the glasses prompt page. */
    @Synchronized fun accept(left: ShortArray, right: ShortArray) {
        if (!active || mediaSessionId != null) return
        // Bidirectional: either speaker may be on either microphone, so feed one mixed stream.
        val mixed = if (right.size == left.size) ShortArray(left.size) { ((left[it].toInt() + right[it].toInt()) / 2).toShort() } else left
        val samples = resampler.convert(mixed)
        val target = asr ?: return
        // Stay live: drop audio rather than queueing behind a slow or reconnecting ASR socket.
        if (!target.canAcceptAudio()) return
        try { target.append(samples) } catch (error: Exception) {
            val token = generation
            main.post { asrEnded(token, error.message ?: "ASR 오디오 전송 실패") }
        }
    }

    /** Device-playback PCM is already mono 16 kHz; never run the glasses 48 kHz resampler. */
    @Synchronized fun acceptMedia(id: String, pcm: ShortArray, count: Int): Boolean {
        if (!active || mediaSessionId != id || count !in 1..pcm.size) return false
        val target = asr ?: return false
        if (!target.canAcceptAudio()) return false
        return try { target.append(FloatArray(count) { pcm[it] / 32768f }); true }
        catch (error: Exception) {
            val token = generation
            main.post { asrEnded(token, error.message ?: "미디어 ASR 오디오 처리 실패") }
            false
        }
    }

    /** Must be called on the main thread (service onDestroy). */
    fun close() { stop(); mediaTranslation.close(); offline.close(); translations.shutdownNow(); http.dispatcher.executorService.shutdown() }

    private fun openASR() {
        val token: Int
        val round: SpeechRound
        synchronized(this) {
            if (!active) return
            token = generation
            resampler = ConversationAudio()
            round = SpeechRounds.create(context, config,
                { text, _, _, _, _ -> main.post { finished(token, text) } },
                { message -> main.post { asrEnded(token, message) } },
                { main.post { asrEnded(token, null) } },
                { text -> main.post { interim(token, text) } })
            asr = round
        }
        try { round.start() } catch (error: Exception) { asrEnded(token, error.message ?: "ASR 시작 실패") }
    }

    private fun asrEnded(token: Int, message: String?) {
        synchronized(this) {
            if (token != generation || !active) return
            asr?.cancel(); asr = null
        }
        // A normal end (null) just reopens; errors back off and give up after repeated failures without speech.
        if (message != null) failures++
        if (failures >= MAX_FAILURES) {
            stop("ASR 연결 실패로 ${if (captionOnly) "자막" else "번역"}을 멈췄습니다: $message")
            gaveUp()
            return
        }
        status = if (message == null) "ASR 세션 종료, 다시 연결 중…" else "$message · 다시 연결 중 ($failures/$MAX_FAILURES)…"
        changed()
        restartTask?.let(main::removeCallbacks)
        restartTask = Runnable { restartTask = null; if (token == generation && active) openASR() }
            .also { main.postDelayed(it, 1_500L * maxOf(1, failures)) }
    }

    private fun heard() {
        if (failures != 0 || status != listening()) { failures = 0; status = listening(); changed() }
    }

    private fun listening() = when {
        wakeActive -> "한국어 호출 대기"
        mediaTranslate && mediaTranslation.state.phase == LocalMediaTranslationPhase.ERROR -> "내장 번역 오류 · 원문 표시"
        mediaTranslate -> "휴대폰 재생음 · 내장 한국어 번역"
        mediaSessionId != null -> "휴대폰 재생음 · 로컬 원문 자막"
        captionOnly -> "듣는 중 · 로컬 자막"
        offlineMode -> "듣는 중 · 오프라인 한영 번역"
        else -> LISTENING
    }
    private fun render() {
        if (mediaTranslate) {
            // Never pair a new ASR partial with an older translated sentence.
            when {
                mediaTranslation.state.phase == LocalMediaTranslationPhase.ERROR ->
                    show("번역 오류 · 원문", clip(sourceLine, TARGET_LIMIT))
                mediaFinalTarget.isNotBlank() -> show(clip(mediaFinalSource, SOURCE_LIMIT), clip(mediaFinalTarget, TARGET_LIMIT))
                else -> show("한국어 번역 대기", clip(sourceLine, TARGET_LIMIT))
            }
        } else if (captionOnly) show(if (mediaSessionId != null) "미디어 원문 자막" else "실시간 자막", clip(sourceLine, TARGET_LIMIT))
        else show(clip(sourceLine, SOURCE_LIMIT), targetLine)
    }

    private fun interim(token: Int, text: String) {
        if (token != generation || !active || wakeActive || (mediaSessionId == null && misheard(text))) return
        heard()
        sourceLine = text
        render()
    }

    private fun finished(token: Int, text: String) {
        if (token != generation || !active) return
        heard()
        // Neither Korean nor English (typically noise or Korean misread as Chinese): never show or translate it.
        if (mediaSessionId == null && misheard(text)) return
        wakeWindow?.let { window ->
            val question = window.accept(text, android.os.SystemClock.elapsedRealtime())
            if (question != null) {
                val action = wakeAction
                stop("한국어 호출 인식 완료")
                gaveUp()
                action?.invoke(question)
            }
            return
        }
        if (mediaTranslate) {
            val id = mediaSessionId ?: return
            if (text.isBlank()) return
            val sequence = ++latestFinal
            sourceLine = text
            val accepted = mediaTranslation.submit(id, mediaLanguage, text) { result ->
                if (token != generation || !active || mediaSessionId != id || sequence <= mediaRenderedSequence) return@submit
                result.fold({ translated ->
                    mediaRenderedSequence = sequence
                    mediaFinalSource = text; mediaFinalTarget = translated
                }, { mediaFinalSource = ""; mediaFinalTarget = "" })
                status = listening()
                render(); changed()
            }
            if (!accepted) { mediaFinalSource = ""; mediaFinalTarget = ""; status = listening(); changed() }
            render()
            return
        }
        if (captionOnly) { sourceLine = text; render(); return }
        val sequence = ++latestFinal
        val from = if (isKorean(text)) "Korean" else "English"
        val to = if (from == "Korean") "English" else "Korean"
        sourceLine = text; targetLine = "…"
        render()
        if (offlineMode) {
            offline.translate(text, to) { result ->
                if (token != generation || !active || sequence != latestFinal) return@translate
                result.fold({ translated -> targetLine = clip(translated, TARGET_LIMIT); render() },
                    { targetLine = "(로컬 번역 실패)"; render(); status = "로컬 번역 실패 · 모델 서버 확인"; changed() })
            }
            return
        }
        val context = history.toList()
        val snapshot = config
        translations.execute {
            // Skip sentences already superseded by a newer final: only the latest translation is ever shown.
            if (token != generation || sequence != latestFinal) return@execute
            val result = runCatching { translate(token, snapshot, text, from, to, context) }
            main.post {
                if (token != generation || !active) return@post
                result.fold({ translated ->
                    history.addLast(text to translated); while (history.size > 3) history.removeFirst()
                    if (sequence == latestFinal) { targetLine = clip(translated, TARGET_LIMIT); render() }
                }, { error ->
                    if (sequence == latestFinal) { targetLine = "(번역 실패)"; render() }
                    status = "번역 실패: ${error.message ?: error.javaClass.simpleName}"
                    changed()
                })
            }
        }
    }

    private fun translate(token: Int, settings: JSONObject, text: String, from: String, to: String, context: List<Pair<String, String>>): String {
        val endpoint = MobileModelEndpoint.resolve(settings.optString("modelUrl"), settings.optString("modelKey"))
        val previous = if (context.isEmpty()) "" else "\nRecent conversation for context only (do not translate it again):\n" +
            context.joinToString("\n") { (source, target) -> "- $source => $target" }
        val system = "You are a live interpreter between Korean and English shown on smart glasses. " +
            "Translate the user's utterance from $from to $to. Output only the translation in natural spoken $to, " +
            "with no quotes, notes, romanization or explanations. Keep names and numbers accurate.$previous"
        val body = JSONObject().put("model", settings.getString("modelName")).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
        val request = Request.Builder().url(endpoint).apply {
            settings.optString("modelKey").takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val current = http.newCall(request); call = current
        // stop() may have run between the generation check and assigning the call.
        if (token != generation) current.cancel()
        return current.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            JSONObject(checkNotNull(response.body).string()).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim().trim('"').ifBlank { error("빈 번역 결과") }
        }
    }

    companion object {
        private const val OFF = "실시간 번역 꺼짐"
        private const val LISTENING = "듣는 중 · 한국어 ↔ 영어"
        private const val MAX_FAILURES = 5
        private const val SOURCE_LIMIT = 80
        private const val TARGET_LIMIT = 120
        fun clip(text: String, limit: Int): String { val value = text.trim(); return if (value.length <= limit) value else "…" + value.takeLast(limit - 1) }
        /** True when the text has no Hangul or Latin letters but does have CJK ideographs or kana. */
        fun misheard(text: String) = text.none { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' } &&
            text.any { it in '一'..'鿿' || it in '㐀'..'䶿' || it in '぀'..'ヿ' }
        /** Hangul share of letters decides the direction; mixed or Latin-only utterances count as English. */
        fun isKorean(text: String): Boolean {
            val hangul = text.count { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
            val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
            return hangul > 0 && hangul * 2 >= latin
        }
    }
}
