// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Encrypted Agent outbox. Offline queued work survives phone/service restarts. */
internal class DshRelay(private val main: Handler, private val repository: ConversationRepository,
    private val restored: (String, String, JSONObject) -> Unit, private val progress: (String) -> Unit) {
    private data class Request(val id: String, val text: String, val context: String,
        val complete: (Result<String>) -> Unit, val kind: String, val input: JSONObject?,
        var state: String = "queued", var session: String = "", var claimedAt: Long = 0L, var answer: String? = null, var delivered: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val hintDeadline: Long = 0L,
        val preview: ((String) -> Unit)? = null, val previewGate: HintStreamPolicy.Gate = HintStreamPolicy.Gate()) {
        val explicitHint: Boolean get() = kind == "hint" && input?.optString("purpose") in setOf("record_followup", "agent_probe", "korean_wake")
        fun json() = JSONObject().put("id", id).put("text", text).put("context", context).put("kind", kind).put("input", input)
            .put("state", state).put("sessionId", session).put("claimedAt", claimedAt).put("answer", answer).put("delivered", delivered).put("createdAt", createdAt)
    }
    private val lock = Object()
    private val requests = linkedMapOf<String, Request>()
    init {
        val saved = repository.readAgentOutbox()
        var historicalCancelled = false
        repeat(saved.length()) { index ->
            val row = saved.getJSONObject(index); val input = row.optJSONObject("input")
            val text = row.getString("text")
            val request = Request(row.getString("id"), text, row.getString("context"), { result ->
                result.onSuccess { restored(text, it, input ?: JSONObject()) }
            }, row.optString("kind", "task"), input, row.optString("state", "queued"), row.optString("sessionId"), row.optLong("claimedAt"), row.optString("answer").takeUnless { it == "null" }, row.optBoolean("delivered"), row.optLong("createdAt", row.optLong("claimedAt")))
            if (input?.optBoolean("backfill") == true && request.state in setOf("queued", "running")) {
                request.state = "cancelled"; historicalCancelled = true
            }
            requests[request.id] = request
            if (request.state == "running") scheduleTimeout(request)
            if (request.state == "completed" && !request.delivered && request.answer != null) deliver(request)
        }
        if (historicalCancelled) persist()
    }
    private fun persist() {
        val finished = requests.values.filter { it.state !in setOf("queued", "running") }
        val prune = finished.filter { it.hintDeadline == 0L }.dropLast(64) +
            finished.filter { it.hintDeadline > 0L }.dropLast(16)
        prune.forEach { requests.remove(it.id) }
        repository.saveAgentOutbox(JSONArray(requests.values.filter { it.hintDeadline == 0L }.map { it.json() }))
    }
    fun submit(text: String, context: String, kind: String = "task", input: JSONObject? = null,
               hintLifetimeMs: Long = 0L, preview: ((String) -> Unit)? = null, complete: (Result<String>) -> Unit): String {
        require(input?.optBoolean("backfill") != true) { "과거 보충 전사는 새 AI 업무 요청으로 실행하지 않습니다" }
        require(hintLifetimeMs in 0L..ProactiveHintPolicy.MAX_AGE_MS && (hintLifetimeMs == 0L || kind == "hint"))
        require(preview == null || (hintLifetimeMs > 0L && input?.optBoolean("streamHint") == true))
        val id = UUID.randomUUID().toString()
        if (input?.optString("recordId")?.isNotBlank() == true) input.put("derivedId", id)
        val request = Request(id, text, context, complete, kind, input,
            hintDeadline = if (hintLifetimeMs > 0L) SystemClock.elapsedRealtime() + hintLifetimeMs else 0L, preview = preview)
        synchronized(lock) {
            expireHints()
            if (request.hintDeadline > 0L && !request.explicitHint) {
                // Ambient updates may coalesce, but must never replace an explicit review or diagnostic.
                requests.values.filter { it.hintDeadline > 0L && !it.explicitHint && it.state == "queued" }.forEach { cancelHint(it) }
            }
            requests[request.id] = request; persist(); lock.notifyAll()
        }
        if (request.hintDeadline > 0L) main.postDelayed({
            synchronized(lock) { expireHints(); lock.notifyAll() }
        }, hintLifetimeMs)
        return id
    }
    private fun deliver(request: Request) { main.post {
        request.complete(Result.success(request.answer.orEmpty()))
        synchronized(lock) { request.delivered = true; persist() }
    } }
    // Call only under lock. Transient hints never survive a service restart.
    private fun cancelHint(request: Request) {
        request.state = "cancelled"
        main.post { request.complete(Result.failure(IllegalStateException("선제 힌트 만료 또는 대체"))) }
    }
    private fun expireHints() {
        val now = SystemClock.elapsedRealtime()
        requests.values.filter { it.hintDeadline > 0L && it.state in setOf("queued", "running") &&
            ProactiveHintPolicy.remaining(now, it.hintDeadline) == 0L }.forEach { cancelHint(it) }
    }
    private fun scheduleTimeout(request: Request) {
        val timeout = if (request.kind == "task") 600_000L else 90_000L
        val remaining = (request.claimedAt + timeout - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed({
            val expired = synchronized(lock) {
                if (requests[request.id] === request && request.state == "running") {
                    request.state = "failed"; persist(); true
                } else false
            }
            if (expired) request.complete(Result.failure(IllegalStateException("외부 Agent 처리 시간 초과. DSH에서 이번 작업을 확인하세요")))
        }, remaining)
    }
    fun next(authorized: () -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 20_000
        synchronized(lock) {
            expireHints()
            while (authorized() && requests.values.none { it.state == "queued" }) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return JSONObject().put("request", JSONObject.NULL)
                lock.wait(minOf(remaining, 1_000))
                expireHints()
            }
            check(authorized()) { "Agent 엔드포인트가 닫혔거나 토큰이 변경됨" }
            expireHints()
            // Explicit/durable tasks take precedence over suggestions.
            val request = requests.values.firstOrNull { it.state == "queued" && it.hintDeadline == 0L }
                ?: requests.values.firstOrNull { it.state == "queued" && it.explicitHint }
                ?: requests.values.firstOrNull { it.state == "queued" }
                ?: return JSONObject().put("request", JSONObject.NULL)
            val hintRemaining = if (request.hintDeadline > 0L)
                ProactiveHintPolicy.remaining(SystemClock.elapsedRealtime(), request.hintDeadline) else 0L
            if (request.hintDeadline > 0L && hintRemaining == 0L) {
                cancelHint(request)
                return JSONObject().put("request", JSONObject.NULL)
            }
            request.state = "running"; request.claimedAt = System.currentTimeMillis(); persist()
            if (request.hintDeadline == 0L) {
                scheduleTimeout(request)
                main.post { progress("로컬 외부 Agent가 수신함. 휴대폰 작업과 승인은 Agent 앱에서 확인하세요") }
            }
            val seconds = if (request.hintDeadline > 0L) (hintRemaining + 999L) / 1000L else if (request.kind == "task") 600L else 90L
            return JSONObject().put("request", request.json().put("createdFor", "openrayneo").put("expiresInSeconds", seconds))
        }
    }
    fun list(): JSONArray = synchronized(lock) { JSONArray(requests.values.map { request ->
        JSONObject().put("id", request.id).put("state", request.state).put("kind", request.kind).put("sessionId", request.session)
            .put("claimedAt", request.claimedAt).put("createdAt", request.createdAt).put("recordId", request.input?.optString("recordId"))
            .put("backfill", request.input?.optBoolean("backfill") ?: false)
    }) }
    fun status(id: String): JSONObject = synchronized(lock) {
        expireHints()
        val request = requests[id]
        JSONObject().put("id", id).put("state", request?.state ?: "cancelled").put("sessionId", request?.session.orEmpty())
    }
    fun update(id: String, body: JSONObject): JSONObject = synchronized(lock) {
        expireHints()
        val request = checkNotNull(requests[id]) { "DSH 요청이 만료됨" }
        if (request.hintDeadline > 0L && request.state == "cancelled") return status(id)
        if (body.optString("state") == "partial") {
            if (request.state == "running" && request.hintDeadline > 0L && request.preview != null) {
                request.previewGate.accept(body.optString("answer"), SystemClock.elapsedRealtime())?.let { text ->
                    main.post {
                        val valid = synchronized(lock) {
                            requests[id] === request && request.state == "running" &&
                                ProactiveHintPolicy.remaining(SystemClock.elapsedRealtime(), request.hintDeadline) > 0L
                        }
                        if (valid) request.preview.invoke(text)
                    }
                }
            }
            return status(id) // Preview is never stored or a terminal update.
        }
        if (request.state == "completed" && body.optString("state") == "completed") return status(id)
        check(request.state == "running") { "DSH 요청이 종료되었거나 취소됨" }
        if (body.has("sessionId")) request.session = body.getString("sessionId")
        when (body.getString("state")) {
            "running" -> main.post { progress(body.optString("message", "DSH Go 작업 처리 중")) }
            "completed" -> {
                val answer = body.getString("answer").trim()
                request.state = "completed"; request.answer = answer; persist()
                deliver(request)
            }
            "failed" -> {
                request.state = "failed"; persist()
                main.post { request.complete(Result.failure(IllegalStateException(body.optString("error", "DSH 작업이 완료되지 않았습니다. DSH Go를 열어 확인하세요")))) }
            }
            else -> error("알 수 없는 DSH 작업 상태")
        }
        if (request.state == "running") persist()
        status(id)
    }
    fun cancelRequest(id: String) = cancelMatching { it.id == id }
    fun cancelPassiveHints() = cancelMatching { it.kind == "hint" && it.input?.optString("source") == "ALWAYS_ON" && it.input?.has("hintDeadline") == true }
    fun cancelConversation(key: String) = cancelMatching { it.input?.optString("conversationKey") == key }
    private fun cancelMatching(matches: (Request) -> Boolean) = synchronized(lock) {
        requests.values.filter { it.state in setOf("queued", "running") && matches(it) }.forEach { request ->
            request.state = "cancelled"
            main.post { request.complete(Result.failure(IllegalStateException("DSH 이번 요청이 취소됨. 이미 실행된 작업은 유지됨"))) }
        }
        persist(); lock.notifyAll()
    }
    fun cancel(kind: String? = null) = synchronized(lock) {
        requests.values.filter { it.state in setOf("queued", "running") && (kind == null || kind == it.kind) }.forEach { request ->
            request.state = "cancelled"
            main.post { request.complete(Result.failure(IllegalStateException("DSH 작업이 취소됨. 이미 실행된 작업은 유지됨"))) }
        }
        persist(); lock.notifyAll()
    }
}
