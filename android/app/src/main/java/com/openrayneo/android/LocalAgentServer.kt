// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class AgentServerState(val enabled: Boolean = false, val port: Int = 0,
    val notice: String = "로컬 Agent 엔드포인트 꺼짐", val pollSeen: Boolean = false, val pollRecent: Boolean = false) {
    val receiverStatus: String get() = when {
        !enabled -> "연결 설정 필요"
        pollRecent -> "외부 Agent 수신 요청 확인 · 모델 응답 별도 확인"
        pollSeen -> "최근 수신 요청 없음 · 작업 중일 수 있음"
        else -> "엔드포인트 켜짐 · 외부 Agent 수신 대기"
    }
}
/** Explicitly enabled, loopback-only API. No model credentials, raw audio or voice vectors are returned. */
internal class LocalAgentServer(private val main: Handler, private val settings: AppSettings, private val changed: () -> Unit,
    private val status: () -> JSONObject, private val records: () -> JSONArray,
    private val execute: (String, JSONObject, () -> Boolean, (Result<JSONObject>) -> Unit) -> Unit,
    private val harness: () -> DshRelay,
    private val recordAPI: (String, String, JSONObject) -> JSONObject?) {
    data class Review(val id: String, val name: String, val arguments: String, val expiresAt: Long)
    data class Receipt(val id: String, val name: String, val status: String, val detail: String, val createdAt: Long)
    private data class Task(val name: String, val args: JSONObject, val approval: Boolean,
        val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis(),
        val startedAt: Long = SystemClock.elapsedRealtime(),
        @Volatile var cancelled: Boolean = false, var state: String = "running",
        var phase: String = if (approval) "awaiting_approval" else "executing",
        var result: JSONObject? = null, var error: String? = null)
    private val tasks = linkedMapOf<String, Task>() // Main-thread owned.
    private val reviewMs = 40_000L
    private val taskMs = 120_000L
    val reviews: List<Review> get() = tasks.values.filter { it.state == "running" && it.phase == "awaiting_approval" &&
        SystemClock.elapsedRealtime() - it.startedAt < reviewMs }.map {
        Review(it.id, it.name, it.args.toString(2), it.createdAt + reviewMs)
    }
    // Recent receipts contain only the operation name and status, not private arguments/results.
    val receipts: List<Receipt> get() = tasks.values.filter { it.approval && it.phase != "awaiting_approval" }
        .takeLast(8).asReversed().map { task ->
            val result = task.result
            val status = when {
                task.phase == "executing" -> "실행 중"
                task.phase == "not_executed" -> "미실행"
                task.phase == "outcome_unknown" -> "결과 미확인"
                result?.optBoolean("duplicate") == true -> "중복 요청 · 재조회 필요"
                result?.optBoolean("readBack") == true -> "기기 재조회 확인"
                result?.optBoolean("persisted") == true -> "로컬 저장 확인"
                result?.has("readBack") == true -> "기기 결과 미확인"
                result?.has("queued") == true -> if (result.optBoolean("queued")) "전송 대기열 등록 · 표시 미확인" else "전송 안 됨"
                else -> "도구 응답 반환 · 결과 별도 확인"
            }
            Receipt(task.id, task.name, status, if (task.phase == "outcome_unknown") "이미 실행된 변경이 있을 수 있습니다. 재조회하세요." else "", task.createdAt)
        }
    private fun end(task: Task, reason: String) {
        if (task.state != "running") return
        val executing = task.phase == "executing"
        task.cancelled = true; task.state = "cancelled"
        task.phase = if (executing) "outcome_unknown" else "not_executed"
        task.error = reason + if (executing) " · 이미 실행된 변경은 취소되지 않을 수 있습니다. 재조회하세요." else " · 실행하지 않았습니다."
        changed()
    }
    fun cancelReviewExecution(id: String) {
        val task = checkNotNull(tasks[id]) { "요청이 없거나 만료되었습니다" }
        check(task.approval) { "외부 승인 작업이 아닙니다" }
        end(task, "사용자가 남은 실행을 취소함")
    }
    fun cancelOutstanding(reason: String) {
        tasks.values.toList().forEach { end(it, reason) }
    }
    private fun expire(task: Task) {
        if (task.state != "running") return
        val age = SystemClock.elapsedRealtime() - task.startedAt
        if (age >= taskMs || (task.phase == "awaiting_approval" && age >= reviewMs)) end(task, "요청 시간 만료")
    }
    fun review(id: String, approve: Boolean) {
        val task = checkNotNull(tasks[id]) { "요청이 없거나 만료되었습니다" }
        expire(task)
        check(task.state == "running" && task.phase == "awaiting_approval") { "이미 처리되었거나 만료된 요청입니다" }
        if (!approve) { end(task, "사용자가 거절함"); return }
        task.phase = "executing"; changed()
        executeTask(task)
    }
    private fun executeTask(task: Task) {
        val allowed = { !task.cancelled && SystemClock.elapsedRealtime() - task.startedAt < taskMs }
        fun finish(result: Result<JSONObject>) {
            if (task.state != "running") return
            if (!allowed()) { end(task, "요청 시간 만료 또는 취소"); return }
            task.state = if (result.isSuccess) "completed" else "failed"
            task.result = result.getOrNull(); task.error = result.exceptionOrNull()?.message
            task.phase = if (result.isFailure && task.approval) "outcome_unknown" else if (result.isFailure) "failed" else "returned"
            changed()
        }
        try { execute(task.name, JSONObject(task.args.toString()), allowed, ::finish) }
        catch (error: Exception) { finish(Result.failure(error)) }
    }
    // Canonicalize object key order without altering arrays or values.
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun submitTool(name: String, args: JSONObject): JSONObject {
        val approval = CompanionTools.requiresReview(name)
        val requestId = args.optString("request_id")
        if (CompanionTools.mutates(name)) require(requestId.isNotBlank()) { "쓰기 작업에는 고정 request_id가 필요합니다" }
        if (requestId.isNotBlank()) {
            require(requestId.length <= 256)
            val prior = tasks.values.firstOrNull { it.args.optString("request_id") == requestId }
            if (prior != null) {
                check(prior.name == name && canonical(prior.args) == canonical(args)) { "같은 request_id에 다른 작업을 제출할 수 없습니다" }
                expire(prior)
                return taskJSON(prior)
            }
        }
        check(tasks.size < 32) { "대기 중인 작업이 너무 많습니다. 잠시 후 다시 시도하세요" }
        if (approval) {
            require(args.toString().length <= 16_384) { "휴대폰에서 검토 가능한 요청 길이를 초과했습니다" }
            check(tasks.values.count { it.state == "running" && it.phase == "awaiting_approval" } < 4) { "승인 대기 요청이 너무 많습니다" }
        }
        val task = Task(name, JSONObject(args.toString()), approval)
        tasks[task.id] = task
        main.postDelayed({ expire(task) }, reviewMs)
        main.postDelayed({ expire(task) }, taskMs)
        if (approval) changed() else executeTask(task)
        return taskJSON(task)
    }
    private val network = Executors.newFixedThreadPool(3)
    private val slots = java.util.concurrent.Semaphore(8)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var token = ""
    var state = AgentServerState(); private set
    // A valid long poll demonstrates a receiver, not an authenticated model account or successful inference.
    // 45 seconds allows the existing 20-second long poll, client backoff, and ordinary scheduling delays.
    private val pollExpiry = Runnable {
        if (state.enabled && state.pollRecent) { state = state.copy(pollRecent = false); changed() }
    }
    private fun noteReceiverPoll(owner: ServerSocket, authorized: String?) {
        main.post {
            if (socket !== owner || owner.isClosed || authorized != "Bearer $token" || !state.enabled) return@post
            main.removeCallbacks(pollExpiry)
            main.postDelayed(pollExpiry, 45_000L)
            if (!state.pollRecent) { state = state.copy(pollSeen = true, pollRecent = true); changed() }
        }
    }
    fun hasOutstanding(): Boolean = tasks.values.any { it.state == "running" }
    fun tokenForUser(): String = token
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (socket != null) return
        val config = settings.read()
        check(AiModelSelection.legacy(config)) { "현재 모델은 직접 연결 방식입니다" }
        token = config.optString("localAgentToken").ifBlank {
            Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE).also {
                config.put("localAgentToken", it); settings.save(config)
            }
        }
        val port = config.optInt("localAgentPort", 8766)
        val server = try {
            require(port in 1024..65535) { "로컬 포트 범위 오류" }
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        } catch (error: Exception) {
            token = ""
            state = AgentServerState(notice = "AI 연결 포트 $port 사용 불가 · 기존 연결 앱을 확인하세요")
            changed()
            throw error
        }
        try { config.put("localAgentEnabled", true); settings.save(config) }
        catch (error: Exception) { server.close(); token = ""; throw error }
        socket = server
        state = AgentServerState(true, server.localPort, "로컬에서만 수신 · 요청에는 전용 Bearer 토큰 필요"); changed()
        network.execute {
            try { while (!server.isClosed) { val client = server.accept(); if (!slots.tryAcquire()) client.close() else {
                clients.add(client); network.execute { try { handle(client, server) } finally { clients.remove(client); client.close(); slots.release() } }
            } } }
            catch (error: java.io.IOException) { if (!server.isClosed) main.post { state = state.copy(notice = "Agent 엔드포인트 중단: ${error.message}"); stop() } }
        }
    }
    fun stop(persistSetting: Boolean = true) {
        if (persistSetting) { val config = settings.read(); config.put("localAgentEnabled", false); settings.save(config) }
        socket?.close(); socket = null; token = ""
        main.removeCallbacks(pollExpiry)
        clients.forEach { it.close() }; clients.clear()
        cancelOutstanding("Agent 연결 종료 또는 토큰 변경"); tasks.clear()
        state = AgentServerState(); changed()
    }
    fun rotate() { stop(); val config = settings.read(); config.remove("localAgentToken"); settings.save(config); start() }
    private fun handle(client: Socket, owner: ServerSocket) = client.use { connection ->
        connection.soTimeout = 5_000
        var code = 200
        val response: JSONObject = try {
            val input = BufferedInputStream(connection.getInputStream())
            fun line(): String {
                val out = java.io.ByteArrayOutputStream()
                while (true) { val b = input.read(); require(b >= 0) { "HTTP 헤더가 끝나지 않음" }; if (b == 10) break; out.write(b); require(out.size() <= 8192) }
                return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            }
            val request = line().split(' '); require(request.size == 3)
            val headers = mutableMapOf<String, String>(); var headerBytes = 0
            while (true) { val value = line(); headerBytes += value.length; require(headerBytes <= 16384); if (value.isEmpty()) break
                require(value.contains(':')); headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
            if (socket !== owner || owner.isClosed || token.isEmpty() || headers["authorization"] != "Bearer $token") { code = 401; JSONObject().put("error", "Bearer 토큰이 유효하지 않음") }
            else if (headers.containsKey("origin")) { code = 403; JSONObject().put("error", "웹 페이지의 교차 출처 호출은 허용하지 않음") }
            else {
                require(!headers.containsKey("transfer-encoding")) { "다음을 사용하세요: Content-Length" }
                val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..262144)
                val bytes = input.readNBytes(length); require(bytes.size == length)
                val body = if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
                val authorized = headers["authorization"]
                if (request[0] == "GET" && request[1] == "/v1/harness/next") {
                    noteReceiverPoll(owner, authorized)
                    harness().next { socket === owner && !owner.isClosed && authorized == "Bearer $token" }
                } else mainResult {
                    check(socket === owner && !owner.isClosed && authorized == "Bearer $token") { "Agent 엔드포인트가 닫혔거나 토큰이 변경됨" }
                    tasks.values.toList().forEach(::expire)
                    tasks.entries.removeAll { it.value.state != "running" && SystemClock.elapsedRealtime() - it.value.startedAt > taskMs }
                    val path = request[1]
                    when {
                        path.substringBefore('?').startsWith("/v1/records") || path.startsWith("/v1/conversations/") || path.startsWith("/v1/conversations?") ->
                            recordAPI(request[0], path, body) ?: JSONObject().put("error", "해당 기록 엔드포인트가 없음").also { code = 404 }
                        request[0] == "GET" && path == "/v1/status" -> status()
                        request[0] == "GET" && path == "/v1/tools" -> JSONObject().put("tools", CompanionTools.definitions())
                        request[0] == "GET" && path == "/v1/conversations" -> JSONObject().put("items", records())
                        request[0] == "GET" && path == "/v1/harness/requests" -> JSONObject().put("items", harness().list())
                        request[0] == "GET" && path == "/v1/tasks" -> JSONObject().put("items", JSONArray(tasks.values.map { taskJSON(it).put("createdAt", it.createdAt) }))
                        request[0] == "GET" && path.startsWith("/v1/harness/requests/") -> harness().status(path.substringAfterLast('/'))
                        request[0] == "POST" && path.startsWith("/v1/harness/requests/") -> harness().update(path.substringAfterLast('/'), body)
                        request[0] == "GET" && path.startsWith("/v1/tasks/") -> taskJSON(checkNotNull(tasks[path.substringAfterLast('/')]) { "작업이 없거나 만료됨" })
                        request[0] == "POST" && path.startsWith("/v1/tasks/") && path.endsWith("/cancel") -> {
                            val task = checkNotNull(tasks[path.removeSuffix("/cancel").substringAfterLast('/')])
                            end(task, "요청 취소")
                            taskJSON(task)
                        }
                        request[0] == "POST" && path == "/v1/tools/call" -> {
                            val name = body.getString("name"); val args = body.optJSONObject("arguments") ?: JSONObject()
                            require((0 until CompanionTools.definitions().length()).any { CompanionTools.definitions().getJSONObject(it).getJSONObject("function").getString("name") == name }) { "알 수 없는 도구" }
                            submitTool(name, args)
                        }
                        else -> { code = 404; JSONObject().put("error", "해당 API 엔드포인트가 없음") }
                    }
                }
            }
        } catch (error: Exception) { code = 400; JSONObject().put("error", error.message ?: "요청이 완료되지 않음") }
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        try { connection.getOutputStream().apply {
            write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(bytes); flush()
        } } catch (_: java.io.IOException) { /* Client closed its own request. */ }
    }
    private fun mainResult(action: () -> JSONObject): JSONObject {
        val latch = CountDownLatch(1); var result: Result<JSONObject>? = null
        val deadline = SystemClock.elapsedRealtime() + 5_000
        main.post {
            result = runCatching {
                check(SystemClock.elapsedRealtime() < deadline) { "HTTP 요청 대기 시간 만료 · 실행하지 않음" }
                action()
            }; latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "메인 서비스 응답 시간 초과 · 실행 결과 미확인" }; return checkNotNull(result).getOrThrow()
    }
    private fun taskJSON(task: Task) = JSONObject().put("id", task.id).put("state", task.state)
        .put("phase", task.phase).put("result", task.result).put("error", task.error)
        .apply { if (task.phase == "awaiting_approval") put("approvalRequired", true).put("approvalExpiresAt", task.createdAt + reviewMs) }
    fun close() { stop(false); network.shutdownNow() }
}
