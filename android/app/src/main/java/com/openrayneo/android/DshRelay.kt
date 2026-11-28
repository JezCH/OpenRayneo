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
    private val restored: (String, String, JSONObject) -> Unit, private val selectedConfig: () -> JSONObject, private val progress: (String) -> Unit) {
    private val directInference = DirectModelInference()
    private data class Request(val id: String, val text: String, val context: String,
        val complete: (Result<String>) -> Unit, val kind: String, val input: JSONObject?,
        var state: String = "queued", var session: String = "", var claimedAt: Long = 0L, var answer: String? = null, var delivered: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val hintDeadline: Long = 0L,
        val preview: ((String) -> Unit)? = null, val previewGate: HintStreamPolicy.Gate = HintStreamPolicy.Gate(),
        var modelRoute: AgentProbePolicy.ModelRoute? = null,
        var automaticContext: (() -> JSONObject?)? = null,
        val queuedSinceElapsed: Long = SystemClock.elapsedRealtime(), val direct: Boolean = false) {
        val explicitHint: Boolean get() = kind == "hint" && input?.optString("purpose") in setOf("record_followup", "agent_probe", "korean_wake", "explicit_question")
        fun json() = JSONObject().put("id", id).put("text", text).put("context", context).put("kind", kind).put("input", input)
            .put("state", state).put("sessionId", session).put("claimedAt", claimedAt).put("answer", answer).put("delivered", delivered).put("createdAt", createdAt)
    }
    private companion object { const val UNCLAIMED_TASK_MS = 120_000L }
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
            if (request.state == "queued" && request.kind == "task") scheduleUnclaimed(request)
            if (request.state == "completed" && !request.delivered && request.answer != null) deliver(request)
        }
        synchronized(lock) { expireUnclaimedTasks(); if (historicalCancelled) persist() }
    }
    private fun persist() {
        val finished = requests.values.filter { it.state !in setOf("queued", "running") }
        val prune = finished.filter { it.hintDeadline == 0L }.dropLast(64) +
            finished.filter { it.hintDeadline > 0L }.dropLast(16)
        prune.forEach { requests.remove(it.id) }
        repository.saveAgentOutbox(JSONArray(requests.values.filter { it.hintDeadline == 0L && !it.direct }.map { it.json() }))
    }
    fun submit(text: String, context: String, kind: String = "task", input: JSONObject? = null,
               hintLifetimeMs: Long = 0L, preview: ((String) -> Unit)? = null,
               automaticContext: (() -> JSONObject?)? = null, complete: (Result<String>) -> Unit): String {
        require(input?.optBoolean("backfill") != true) { "과거 보충 전사는 새 AI 업무 요청으로 실행하지 않습니다" }
        // Ordinary explicit chat may need a longer reasoning window; passive hints still expire in 60s.
        val ordinaryChat = kind == "hint" && input?.optString("purpose") == "explicit_question" && input.optJSONArray("history") != null
        require(automaticContext == null || OrdinaryContextPolicy.eligible(kind, input?.optString("purpose").orEmpty(),
            input?.optJSONArray("history") != null, input?.has("references") == true, input?.optBoolean("backfill") == true, hintLifetimeMs))
        val maxLifetime = if (ordinaryChat) MobileAgent.CHAT_TIMEOUT_MS else ProactiveHintPolicy.MAX_AGE_MS
        require(hintLifetimeMs in 0L..maxLifetime && (hintLifetimeMs == 0L || kind == "hint"))
        require(preview == null || (hintLifetimeMs > 0L && input?.optBoolean("streamHint") == true))
        val id = UUID.randomUUID().toString()
        if (input?.optString("recordId")?.isNotBlank() == true) input.put("derivedId", id)
        val config = selectedConfig()
        val useDirect = !AiModelSelection.legacy(config)
        val profile = if (useDirect) checkNotNull(AiModelSelection.direct(config)) {
            "선택 모델 연결 설정이 필요합니다."
        } else null
        check(!useDirect || (kind == "hint" && hintLifetimeMs > 0)) {
            "선택 모델은 질문·요약 전용입니다. 외부 업무 실행 연결은 아직 지원하지 않습니다."
        }
        val request = Request(id, text, context, complete, kind, input,
            hintDeadline = if (hintLifetimeMs > 0L) SystemClock.elapsedRealtime() + hintLifetimeMs else 0L, preview = preview,
            automaticContext = automaticContext, direct = useDirect,
            state = if (useDirect) "running" else "queued")
        synchronized(lock) {
            expireHints()
            if (request.hintDeadline > 0L && !request.explicitHint) {
                // Ambient updates may coalesce, but must never replace an explicit review or diagnostic.
                requests.values.filter { it.hintDeadline > 0L && !it.explicitHint && (it.state == "queued" || (it.direct && it.state == "running")) }.forEach { cancelHint(it) }
            }
            requests[request.id] = request; persist(); lock.notifyAll()
        }
        if (request.hintDeadline > 0L) main.postDelayed({
            synchronized(lock) { expireHints(); lock.notifyAll() }
        }, hintLifetimeMs)
        if (profile != null) {
            // Never hand a direct request to the DSH poller. The selected profile
            // is immutable for this request, and replies from a previous selection are discarded.
            val valid = {
                synchronized(lock) { requests[id] === request && request.state == "running" &&
                    SystemClock.elapsedRealtime() < request.hintDeadline } &&
                    runCatching { AiModelSelection.direct(selectedConfig()) == profile }.getOrDefault(false)
            }
            directInference.submit(id, profile, text, context, input, automaticContext, valid) { result ->
                main.post {
                    val accepted = synchronized(lock) {
                        if (requests[id] !== request || request.state != "running") false
                        else if (!valid()) { cancelHint(request); false }
                        else {
                            request.state = if (result.isSuccess) "completed" else "failed"
                            if (input?.optString("purpose") == "agent_probe") {
                                result.getOrNull()?.reportedModel?.let { actual ->
                                    request.modelRoute = AgentProbePolicy.ModelRoute.from(profile.label, actual,
                                        if (input.optString("probeRoute") == "ambient") "ambient_default" else "default")
                                }
                            }
                            request.automaticContext = null
                            persist()
                            true
                        }
                    }
                    if (accepted) complete(result.map { it.text })
                }
            }
        } else if (kind == "task") scheduleUnclaimed(request)
        return id
    }
    private fun deliver(request: Request) { main.post {
        request.complete(Result.success(request.answer.orEmpty()))
        synchronized(lock) { request.delivered = true; persist() }
    } }
    // Call only under lock. Transient hints never survive a service restart.
    private fun cancelHint(request: Request) {
        if (request.direct) directInference.cancel(request.id)
        request.state = "cancelled"
        request.automaticContext = null
        main.post { request.complete(Result.failure(IllegalStateException("선제 힌트 만료 또는 대체"))) }
    }
    // Do not replay a durable task that was never claimed in a reasonable time.
    // Preserve claimed work and its existing timeout/approval semantics.
    private fun remainingUnclaimed(request: Request): Long {
        val wallAge = System.currentTimeMillis() - request.createdAt
        val elapsedAge = SystemClock.elapsedRealtime() - request.queuedSinceElapsed
        if (wallAge < 0L || elapsedAge < 0L) return 0L
        return (UNCLAIMED_TASK_MS - maxOf(wallAge, elapsedAge)).coerceAtLeast(0L)
    }
    private fun expireUnclaimedTasks() {
        val expired = requests.values.filter { it.kind == "task" && it.state == "queued" && remainingUnclaimed(it) == 0L }
        if (expired.isEmpty()) return
        expired.forEach { request ->
            if (request.direct) directInference.cancel(request.id)
            request.state = "cancelled"; request.automaticContext = null
            main.post { request.complete(Result.failure(IllegalStateException("외부 Agent가 2분 안에 업무를 수신하지 않아 취소했습니다. 다시 요청하세요."))) }
        }
        persist(); lock.notifyAll()
    }
    private fun scheduleUnclaimed(request: Request) {
        main.postDelayed({ synchronized(lock) { expireUnclaimedTasks() } }, remainingUnclaimed(request))
    }
    private fun expireHints() {
        expireUnclaimedTasks()
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
        if (!AiModelSelection.legacy(selectedConfig())) return JSONObject().put("request", JSONObject.NULL)
        val deadline = System.currentTimeMillis() + 20_000
        val selected = synchronized(lock) {
            expireHints()
            while (authorized() && requests.values.none { it.state == "queued" }) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return JSONObject().put("request", JSONObject.NULL)
                lock.wait(minOf(remaining, 1_000))
                expireHints()
            }
            check(authorized()) { "Agent 엔드포인트가 닫혔거나 토큰이 변경됨" }
            if (!AiModelSelection.legacy(selectedConfig())) return JSONObject().put("request", JSONObject.NULL)
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
            val provider = request.automaticContext
            request.automaticContext = null
            if (provider == null) return response(request, hintRemaining)
            request to provider
        }
        // Search only after an authenticated client claims the explicit question. No expensive
        // repository traversal under the lock, and no attachment to the durable outbox.
        val (request, provider) = selected
        val prepared = runCatching { provider() }.getOrNull()
        return synchronized(lock) {
            expireHints()
            if (!authorized()) {
                if (request.state == "running") cancelHint(request)
                error("Agent 엔드포인트가 닫혔거나 토큰이 변경됨")
            }
            if (requests[request.id] !== request || request.state != "running")
                return@synchronized JSONObject().put("request", JSONObject.NULL)
            val remaining = ProactiveHintPolicy.remaining(SystemClock.elapsedRealtime(), request.hintDeadline)
            if (remaining == 0L) {
                cancelHint(request)
                return@synchronized JSONObject().put("request", JSONObject.NULL)
            }
            response(request, remaining, prepared)
        }
    }
    private fun response(request: Request, hintRemaining: Long, automatic: JSONObject? = null): JSONObject {
        val seconds = if (request.hintDeadline > 0L) (hintRemaining + 999L) / 1000L else if (request.kind == "task") 600L else 90L
        val payload = request.json().put("createdFor", "openrayneo").put("expiresInSeconds", seconds)
        if (automatic != null) {
            val input = JSONObject(checkNotNull(request.input).toString()).put("automaticContext", automatic)
            payload.put("input", input)
        }
        return JSONObject().put("request", payload)
    }
    fun list(): JSONArray = synchronized(lock) { expireUnclaimedTasks(); JSONArray(requests.values.map { request ->
        JSONObject().put("id", request.id).put("state", request.state).put("kind", request.kind).put("sessionId", request.session)
            .put("claimedAt", request.claimedAt).put("createdAt", request.createdAt).put("recordId", request.input?.optString("recordId"))
            .put("backfill", request.input?.optBoolean("backfill") ?: false)
    }) }
    fun probeModelRoute(id: String?): AgentProbePolicy.ModelRoute? = synchronized(lock) { id?.let { requests[it]?.modelRoute } }
    fun status(id: String): JSONObject = synchronized(lock) {
        expireHints()
        val request = requests[id]
        JSONObject().put("id", id).put("state", request?.state ?: "cancelled").put("sessionId", request?.session.orEmpty())
    }
    fun update(id: String, body: JSONObject): JSONObject = synchronized(lock) {
        expireHints()
        val request = checkNotNull(requests[id]) { "DSH 요청이 만료됨" }
        check(!request.direct) { "직접 모델 요청은 외부 Agent가 갱신할 수 없습니다" }
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
        if (request.kind == "hint" && request.input?.optString("purpose") == "agent_probe" &&
            body.optString("state") in setOf("completed", "failed")) {
            body.optJSONObject("modelRoute")?.let {
                request.modelRoute = AgentProbePolicy.ModelRoute.from(it.optString("provider"), it.optString("model"), it.optString("selection"))
            }
        }
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
    /** Explicit export only. Never include input/context, IDs, references,
     * automatic/passive hints or no-retention questions. Does not mutate outbox. */
    fun diagnosticRequests(includeText: Boolean): String = synchronized(lock) {
        val rows = requests.values.toList().takeLast(40)
        if (!includeText) rows.joinToString("\n") { r ->
            "at=${r.createdAt} kind=${r.kind} state=${r.state} direct=${r.direct} delivered=${r.delivered}"
        } else rows.filter { r ->
            val input = r.input
            (r.kind == "task" && (input == null || input.optString("source") == "NATIVE") && input?.optBoolean("backfill") != true) ||
                (input?.optString("purpose") == "explicit_question" && input.optJSONArray("history") != null)
        }.joinToString("\n\n") { r ->
            "at=${r.createdAt} kind=${r.kind} state=${r.state}\nQ: ${r.text.take(4000)}\nA: ${r.answer.orEmpty().take(6000)}"
        }
    }
    fun hasOutstanding(): Boolean = synchronized(lock) { expireHints(); requests.values.any { it.state in setOf("queued", "running") } }
    fun closeDirect() { directInference.close() }
    fun cancelRequest(id: String) = cancelMatching { it.id == id }
    fun cancelPassiveHints() = cancelMatching { it.kind == "hint" && it.input?.optString("source") == "ALWAYS_ON" && it.input?.has("hintDeadline") == true }
    fun cancelConversation(key: String) = cancelMatching { it.input?.optString("conversationKey") == key }
    private fun cancelMatching(matches: (Request) -> Boolean) = synchronized(lock) {
        requests.values.filter { it.state in setOf("queued", "running") && matches(it) }.forEach { request ->
            if (request.direct) directInference.cancel(request.id)
            request.state = "cancelled"; request.automaticContext = null
            main.post { request.complete(Result.failure(IllegalStateException("DSH 이번 요청이 취소됨. 이미 실행된 작업은 유지됨"))) }
        }
        persist(); lock.notifyAll()
    }
    fun cancel(kind: String? = null) = synchronized(lock) {
        requests.values.filter { it.state in setOf("queued", "running") && (kind == null || kind == it.kind) }.forEach { request ->
            if (request.direct) directInference.cancel(request.id)
            request.state = "cancelled"; request.automaticContext = null
            main.post { request.complete(Result.failure(IllegalStateException("DSH 작업이 취소됨. 이미 실행된 작업은 유지됨"))) }
        }
        persist(); lock.notifyAll()
    }
}
