// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject

/** Android local-ASR adapter for the existing macOS type-9 audio / type-8 progress protocol. */
internal class TeleprompterFollow(private val context: Context,
    private val send: (String, Int, Int, () -> Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val status: (String, Int, Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private class Session(val id: String, val matcher: TeleprompterSpeechMatcher, val lines: IntArray,
                          val config: JSONObject, val allowed: () -> Boolean) {
        @Volatile var round: SpeechRound? = null
        var resampler = ConversationAudio()
        @Volatile var epoch = 0L
        @Volatile var paused = true
        var offset = 0
        var pending: Int? = null
        var sending = false
        var scheduled = false
        var lastSend = -200L
        var started = 0L
    }
    @Volatile private var active: Session? = null
    private val audio = PromptAudio { left, _ ->
        val s = active
        val round = s?.round
        if (s != null && round != null && active === s) {
            try {
                check(round.canAcceptAudio()) { "기기 ASR 처리 지연" }
                round.append(s.resampler.convert(left))
            } catch (error: Exception) {
                val epoch = s.epoch
                main.post { if (current(s, epoch)) suspend("따라 읽기 중지: ${error.message ?: "음성 처리 실패"}") }
            }
        }
    }
    companion object {
        const val MAX_BYTES = 100_000
        fun validate(context: Context, config: JSONObject, text: String) {
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "따라 읽기 원고는 100,000 UTF-8 바이트 이하로 지정하세요" }
            check(SpeechRounds.model(config) != null && SpeechRounds.ready(context, config)) { "음성 설정에서 설치된 기기 내장 ASR을 먼저 선택하세요" }
        }
        private fun lines(text: String): IntArray {
            val paint = TextPaint().apply { textSize = 22f }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 492)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            // Same nominal width/font as macOS; actual firmware wrapping remains device-dependent.
            val result = IntArray(layout.lineCount.coerceAtLeast(1))
            var last = 0; var bytes = 0
            for (i in 0 until layout.lineCount) {
                val start = layout.getLineStart(i).let { if (it > 0 && it < text.length && text[it].isLowSurrogate()) it - 1 else it }
                bytes += text.substring(last, start).toByteArray(Charsets.UTF_8).size
                result[i] = bytes; last = start
            }
            return result
        }
    }
    fun start(id: String, text: String, config: JSONObject, allowed: () -> Boolean) {
        validate(context, config, text)
        stop()
        val s = Session(id, TeleprompterSpeechMatcher(text), lines(text), JSONObject(config.toString()), allowed)
        active = s
        resume()
    }
    fun accept(packet: ByteArray) { if (active?.round != null && packet.isNotEmpty()) audio.accept(packet) }
    private fun current(s: Session, epoch: Long) = active === s && s.epoch == epoch && !s.paused
    fun pause(offset: Int? = null) {
        val s = active ?: return
        suspend("따라 읽기 일시정지")
        if (offset != null) { s.offset = offset.coerceIn(0, s.matcher.totalBytes); s.matcher.seek(s.offset) }
        status("따라 읽기 일시정지", s.offset, s.matcher.totalBytes)
    }
    fun suspend(reason: String) {
        val s = active ?: return
        s.paused = true; s.epoch++; s.pending = null; s.sending = false; s.scheduled = false; s.matcher.seek(s.offset)
        val old = s.round; s.round = null; old?.cancel(); audio.stop()
        status(reason, s.offset, s.matcher.totalBytes)
    }
    fun resume() {
        val s = active ?: return
        if (!s.paused) return
        if (!s.allowed()) { status("따라 읽기 재개 조건 미충족", s.offset, s.matcher.totalBytes); return }
        s.epoch++; val epoch = s.epoch; s.paused = false; s.resampler = ConversationAudio()
        try {
            val round = SpeechRounds.create(context, s.config,
                { text, _, _, _, _ -> onSpeech(s, epoch, text, true) },
                { error -> main.post { if (current(s, epoch)) suspend("따라 읽기 ASR 실패: $error") } },
                { main.post { if (current(s, epoch)) suspend("따라 읽기 ASR 종료") } },
                { text -> onSpeech(s, epoch, text, false) })
            s.round = round; round.start(); audio.start(1); s.started = SystemClock.elapsedRealtime()
            status("따라 읽기: 안경 음성 대기", s.offset, s.matcher.totalBytes)
            tick(s, epoch)
        } catch (error: Exception) { suspend("따라 읽기 시작 실패: ${error.message}") }
    }
    private fun tick(s: Session, epoch: Long) {
        main.postDelayed({
            if (!current(s, epoch)) return@postDelayed
            if (!s.allowed()) { suspend("따라 읽기 중지: 화면 또는 연결 변경"); return@postDelayed }
            val a = audio.state
            val now = SystemClock.elapsedRealtime()
            if (a.error != null || now - (a.lastAudio.takeIf { it > 0 } ?: s.started) > 10_000L) {
                suspend(a.error ?: "따라 읽기 음성 수신 없음. 일시정지 후 재개하세요")
                return@postDelayed
            }
            tick(s, epoch)
        }, 500L)
    }
    private fun onSpeech(s: Session, epoch: Long, text: String, final: Boolean) {
        main.post {
            if (!current(s, epoch) || !s.allowed()) return@post
            val match = s.matcher.match(text, final) ?: return@post
            s.pending = match.byteOffset
            pump(s, epoch)
        }
    }
    private fun pump(s: Session, epoch: Long) {
        if (!current(s, epoch) || !s.allowed() || s.sending || s.scheduled || s.pending == null) return
        val delay = (200L - (SystemClock.elapsedRealtime() - s.lastSend)).coerceAtLeast(0L)
        if (delay > 0) {
            s.scheduled = true
            main.postDelayed({ if (current(s, epoch)) { s.scheduled = false; pump(s, epoch) } }, delay)
            return
        }
        val offset = checkNotNull(s.pending); s.pending = null; s.sending = true; s.lastSend = SystemClock.elapsedRealtime()
        send(s.id, TeleprompterSpeechMatcher.pageStart(s.lines, offset), offset, { current(s, epoch) && s.allowed() }) { result ->
            if (!current(s, epoch)) return@send
            s.sending = false
            if (result.isFailure) suspend("따라 읽기 위치 전송 실패: ${result.exceptionOrNull()?.message}")
            else { s.offset = offset; status("따라 읽기 위치 전송됨 (표시 미확인)", offset, s.matcher.totalBytes); pump(s, epoch) }
        }
    }
    fun stop() {
        val s = active; active = null
        s?.let { it.epoch++; val old = it.round; it.round = null; old?.cancel() }
        audio.stop(); main.removeCallbacksAndMessages(null)
    }
    fun close() { stop(); audio.close() }
}
