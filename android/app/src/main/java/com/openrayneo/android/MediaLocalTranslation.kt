// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import io.github.ngoroth.offlinetranslator.mozilla.MozillaNativeRuntime
import java.util.concurrent.Executors

/** Main-thread session owner, one native worker. One in-flight and one latest pending final.
 * Model preparation reads the APK's bundled assets, never a network.
 */
internal class MediaLocalTranslation(context: Context, private val main: Handler, private val changed: () -> Unit) {
    private val models = MozillaMediaModels(context.applicationContext)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "openrayneo-local-mt") }
    private var runtime: MozillaNativeRuntime? = null // worker only
    @Volatile private var closed = false
    @Volatile private var cancelPrepare = false
    @Volatile private var activeSession: String? = null
    private var preparationId = 0L // main only
    private val lock = Any()
    private var pending: Job? = null
    private var draining = false
    private data class Job(val id: String, val steps: List<String>, val text: String, val callback: (Result<String>) -> Unit)
    var state = LocalMediaTranslationState(); private set

    fun prepare(language: String) {
        check(!closed && activeSession == null && !state.preparing) { "현재 번역 또는 모델 준비를 먼저 종료하세요" }
        require(MediaTranslationRoute.steps(language).isNotEmpty()) { "한국어 원문에는 번역 모델이 필요하지 않습니다" }
        if (state.readyFor(language)) return
        val ticket = ++preparationId
        cancelPrepare = false
        publish(LocalMediaTranslationState(LocalMediaTranslationPhase.VERIFYING, language, "설치된 번역 모델 확인 중"))
        worker.execute {
            val cancelled = { closed || cancelPrepare }
            val result = runCatching {
                releaseRuntime()
                val config = models.configurations(language, cancelled) { phase, received, total ->
                    main.post {
                        if (!closed && !cancelPrepare && ticket == preparationId) publish(state.copy(
                            phase = phase, received = received, total = total,
                            detail = "내장 번역 모델 준비 중"))
                    }
                }
                check(!cancelled()) { "번역 모델 준비 취소" }
                main.post {
                    if (!closed && !cancelPrepare && ticket == preparationId)
                        publish(state.copy(phase = LocalMediaTranslationPhase.LOADING, detail = "기기 내장 번역 엔진 로딩 중"))
                }
                val candidate = MozillaNativeRuntime()
                try {
                    candidate.load(config.keys.toTypedArray(), config.values.toTypedArray())
                    check(!cancelled()) { "번역 모델 준비 취소" }
                    runtime = candidate
                } catch (failure: Throwable) {
                    runCatching { candidate.close() }; throw failure
                }
            }
            if (cancelled() || result.isFailure) runCatching { releaseRuntime() }
            main.post {
                if (!closed && ticket == preparationId) {
                    if (cancelPrepare) {
                        // Cancel may race native completion; queue cleanup before another prepare.
                        worker.execute { runCatching { releaseRuntime() } }
                        publish(LocalMediaTranslationState(language = language, detail = "모델 준비 취소됨 · 완료된 파일은 유지"))
                    } else result.fold({
                        publish(state.copy(phase = LocalMediaTranslationPhase.READY, detail = "내장 번역 엔진 준비됨"))
                    }, { error ->
                        publish(state.copy(phase = LocalMediaTranslationPhase.ERROR, detail = preparationError(error)))
                    })
                }
            }
        }
    }

    fun cancelPreparation() {
        if (!state.preparing || closed) return
        cancelPrepare = true
        publish(state.copy(phase = LocalMediaTranslationPhase.CANCELLING, detail = "모델 준비 취소 중"))
    }

    fun release() {
        check(!closed && activeSession == null && !state.preparing) { "번역과 모델 준비를 먼저 종료하세요" }
        ++preparationId
        publish(LocalMediaTranslationState(detail = "모델 메모리 해제 · 설치 파일 유지"))
        worker.execute { runCatching { releaseRuntime() } }
    }

    fun begin(id: String, language: String) {
        check(!closed && state.readyFor(language) && activeSession == null) { "내장 번역 모델을 먼저 준비하세요" }
        activeSession = id
        publish(state.copy(completed = 0, skipped = 0, lastMs = null, detail = if (language == MediaTranslationRoute.BILINGUAL) "내장 한영 통역 대기" else "한국어 번역 대기"))
    }

    /** Main thread. Never retain more than one waiting final, and do not publish an old lease. */
    fun submit(id: String, language: String, text: String, callback: (Result<String>) -> Unit): Boolean {
        require(language != MediaTranslationRoute.BILINGUAL)
        return enqueue(id, language, MediaTranslationRoute.steps(language), text, callback)
    }
    fun submitBilingual(id: String, fromKorean: Boolean, text: String, callback: (Result<String>) -> Unit): Boolean =
        enqueue(id, MediaTranslationRoute.BILINGUAL, listOf(if (fromKorean) "ko-en" else "en-ko"), text, callback)
    private fun enqueue(id: String, language: String, steps: List<String>, text: String, callback: (Result<String>) -> Unit): Boolean {
        if (closed || activeSession != id || !state.readyFor(language) || text.isBlank()) return false
        val job = Job(id, steps, text, callback)
        val skipped: Boolean
        var schedule = false
        synchronized(lock) {
            skipped = pending != null
            pending = job
            if (!draining) { draining = true; schedule = true }
        }
        if (skipped) publish(state.copy(skipped = state.skipped + 1, detail = "최신 문장 우선 번역"))
        if (schedule) worker.execute(::drain)
        return true
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val next = pending; pending = null
                if (next == null) draining = false
                next
            } ?: return
            if (closed || activeSession != job.id) continue
            val started = SystemClock.elapsedRealtime()
            val result = runCatching {
                val owner = checkNotNull(runtime)
                var value = job.text
                for (step in job.steps) {
                    if (closed || activeSession != job.id) throw java.util.concurrent.CancellationException()
                    value = owner.translate(step, value)
                }
                value
            }
            val cancelled = result.exceptionOrNull() is java.util.concurrent.CancellationException
            if (result.isFailure && !cancelled) {
                runCatching { releaseRuntime() }
                synchronized(lock) { pending = null }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            main.post {
                if (closed) return@post
                if (result.isFailure && !cancelled && !state.preparing && state.phase == LocalMediaTranslationPhase.READY) {
                    publish(state.copy(phase = LocalMediaTranslationPhase.ERROR, detail = "내장 번역 오류 · 원문 표시 유지, 모델 다시 준비 필요"))
                }
                if (activeSession != job.id || cancelled) return@post
                if (result.isSuccess) publish(state.copy(completed = state.completed + 1, lastMs = elapsed, detail = if (state.language == MediaTranslationRoute.BILINGUAL) "기기 내장 한영 통역" else "기기 내장 한국어 번역"))
                job.callback(result)
            }
        }
    }

    fun end(id: String) {
        if (activeSession != id) return
        activeSession = null
        synchronized(lock) { pending = null }
        if (state.phase == LocalMediaTranslationPhase.READY) publish(state.copy(detail = "내장 번역 엔진 준비됨"))
    }

    fun close() {
        closed = true; cancelPrepare = true; activeSession = null
        synchronized(lock) { pending = null }
        worker.execute { runCatching { releaseRuntime() } }
        worker.shutdown()
    }
    private fun releaseRuntime() { val old = runtime; runtime = null; old?.close() }
    private fun publish(value: LocalMediaTranslationState) { state = value; changed() }
    private fun preparationError(error: Throwable): String = when (error) {
        is io.github.ngoroth.offlinetranslator.mozilla.MozillaNativeException -> "내장 번역 엔진 준비 실패 (${error.code})"
        is OutOfMemoryError -> "번역 모델을 로딩할 메모리가 부족합니다"
        is java.io.IOException -> "내장 번역 모델 읽기 또는 저장 실패 · 설치 APK/저장공간 확인"
        else -> error.message?.takeIf { it.startsWith("번역") || it.startsWith("모델") } ?: "내장 번역 모델 준비 실패"
    }
}
