// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class TeleprompterState(val documents: List<JSONObject> = emptyList(), val active: String? = null,
    val paused: Boolean = false, val busy: Boolean = false, val notice: String = "원고를 저장한 뒤 안경으로 전송",
    val smartFollow: Boolean = false, val followStatus: String = "", val followOffset: Int = 0, val followTotal: Int = 0)
internal class TeleprompterLibrary(private val context: Context, private val requests: GlassesRequests, private val changed: () -> Unit,
    private val followAllowed: () -> Boolean = { true }) {
    private val folder = File(context.filesDir, "scripts").apply { mkdirs() }
    @Volatile var state = TeleprompterState(documents = folder.listFiles().orEmpty().filter { it.extension == "json" }.map { JSONObject(it.readText()) }); private set
    private val messages = LinkedBlockingQueue<Pair<Int, JSONObject>>(64)
    private val transfer = LinkedBlockingQueue<WireFields.Value>(64)
    @Volatile private var task: String? = null
    @Volatile private var disconnected = false
    private var sequence = 0x5300
    @Volatile private var followGeneration = 0L
    private val follow = TeleprompterFollow(context, { id, page, highlight, allowed, complete ->
        requests.withPermission(allowed) {
            requests.perform({ send(8, JSONObject().put("action", 1).put("did", id).put("pageOffset", page).put("highLightOffset", highlight)) }, complete)
        }
    }, { notice, offset, total -> state = state.copy(followStatus = notice, followOffset = offset, followTotal = total); changed() })
    fun save(title: String, text: String, id: String = UUID.randomUUID().toString()): String {
        require(title.isNotBlank() && text.isNotBlank()); require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val document = JSONObject().put("did", id).put("title", title).put("text", text).put("updated", System.currentTimeMillis())
        val file = android.util.AtomicFile(File(folder, "$id.json")); val out = file.startWrite()
        try { out.write(document.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
        state = state.copy(documents = listOf(document) + state.documents.filter { it.getString("did") != id }); changed(); return id
    }
    fun observe(frame: ProtocolCodec.Frame) {
        if (frame.protocol == 0x11 && task != null) {
            WireFields.parse(frame.payload).firstOrNull()?.let { field ->
                val fields = field.bytes?.let(WireFields::parse).orEmpty()
                if (fields.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) == task)
                    check(transfer.offer(field)) { "프롬프터 파일 응답 대기열이 가득 참" }
            }
        } else if (frame.protocol == 0x14) {
            val message = ProtocolCodec.message(frame.payload)
            if (message.type == 9) { if (state.smartFollow && !state.paused && !state.busy) message.audio?.let(follow::accept); return }
            val body = message.json?.let(::JSONObject) ?: return
            if (body.optInt("action") == 2) { if (!messages.offer(message.type to body)) { messages.poll(); messages.offer(message.type to body) } }
            if (state.smartFollow && body.optInt("action") == 1 && body.optString("did") == state.active && message.type in setOf(4, 5, 6)) {
                val id = body.getString("did"); val token = followGeneration
                when (message.type) {
                    4 -> { state = state.copy(paused = true); follow.pause(if (body.optBoolean("isCompleted")) state.followTotal else if (body.has("offset")) body.optInt("offset", state.followOffset) else null) }
                    5 -> { state = state.copy(paused = false); if (!state.busy) follow.resume() }
                    6 -> { follow.stop(); state = state.copy(active = null, paused = false, smartFollow = false, followStatus = "", notice = "안경이 프롬프터를 종료함") }
                }
                changed()
                // The existing macOS controller acknowledges device-initiated pause/resume/stop with action=2.
                requests.withPermission({ !disconnected && token == followGeneration }) {
                    requests.perform({ send(message.type, JSONObject().put("action", 2).put("did", id).put("code", 1)) }) { }
                }
                return
            }
            if (body.optInt("action") == 1 && body.optString("did") == state.active && message.type == 6) {
                state = state.copy(active = null, paused = false, notice = "안경이 프롬프터를 종료함"); changed()
            }
        }
    }
    fun start(id: String, speed: Int, completion: (Result<JSONObject>) -> Unit, followConfig: JSONObject? = null) {
        check(!state.busy && !state.smartFollow) { "현재 프롬프터를 먼저 종료하세요" }
        val text = state.documents.first { it.getString("did") == id }.getString("text")
        if (followConfig != null) {
            check(state.active == null && followAllowed()) { "다른 표시 및 음성 기능을 먼저 종료하세요" }
            TeleprompterFollow.validate(context, followConfig, text)
        }
        val token = ++followGeneration
        state = state.copy(smartFollow = followConfig != null, followStatus = "", followOffset = 0, followTotal = 0)
        operation({ play(id, speed, followConfig != null) }) { result ->
            if (token != followGeneration) { completion(Result.failure(IllegalStateException("프롬프터 연결이 변경됨"))); return@operation }
            if (followConfig != null && result.isSuccess && state.active == id && state.smartFollow) {
                val started = runCatching { follow.start(id, text, followConfig) { token == followGeneration && !disconnected && state.active == id && state.smartFollow && !state.paused && !state.busy && followAllowed() } }
                started.onFailure { state = state.copy(followStatus = it.message ?: "따라 읽기 시작 실패"); changed() }
            } else if (result.isFailure && state.active == null) { state = state.copy(smartFollow = false); changed() }
            completion(result)
        }
    }
    fun startText(title: String, text: String, speed: Int, completion: (Result<JSONObject>) -> Unit) = start(save(title, text), speed, completion)
    private fun play(id: String, speed: Int, smart: Boolean): JSONObject {
        require(speed in 1..10)
        val document = state.documents.first { it.getString("did") == id }
        val text = document.getString("text"); val content = text.toByteArray(Charsets.UTF_8)
        if (state.active != null) controlBlocking("stop")
        messages.clear(); transfer.clear(); disconnected = false
        val scroll = if (smart) 1 else 2
        val settings = JSONObject().put("countdown", 3).put("scroll", scroll).put("speed", speed).put("gear", 1)
            .put("depth", 1).put("size", 18).put("width", 492).put("leading", 4)
        val item = JSONObject().put("did", id).put("pin", false).put("title", document.getString("title")).put("offset", 0)
            .put("scroll", scroll).put("total", content.size).put("timestamp", System.currentTimeMillis() / 1000)
            .put("preview", text.take(80)).put("timezone", ZonedDateTime.now().offset.totalSeconds)
        send(1, JSONObject().put("action", 2).put("type", 3).put("total", state.documents.size)
            .put("chunkinfo", JSONObject().put("chunkid", 1).put("total_items", 1).put("islast", true)).put("items", JSONArray().put(item)))
        send(2, JSONObject(settings.toString()).put("action", 1).put("did", id).put("total", content.size).put("pageOffset", 0).put("highLightOffset", 0))
        state = state.copy(active = id, paused = true)
        val taskId = "task_2_${System.currentTimeMillis()}"; task = taskId
        try {
            // MD5 and FNV are required wire fields, matching the official protocol.
            val md5 = MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            val info = WireFields.string(1, taskId) + WireFields.string(2, id) + WireFields.integer(3, content.size.toLong()) + WireFields.string(4, md5) + WireFields.integer(5, 17)
            requests.sendRaw(ProtocolCodec.frame(17, 0, WireFields.bytes(1, info)))
            while (true) {
                check(!disconnected) { "연결이 끊겨 프롬프터 전송이 취소됨" }
                val field = checkNotNull(transfer.poll(30, TimeUnit.SECONDS)) { "안경이 파일 데이터를 요청하지 않아 전송 시간 초과" }
                val values = WireFields.parse(checkNotNull(field.bytes))
                fun number(n: Int) = values.firstOrNull { it.number == n }?.integer
                if (field.number == 6) error("안경이 프롬프터 파일 전송을 취소함")
                if (field.number == 5) { check(number(2) == 0L) { "안경이 파일 전송을 거부함" }; break }
                if (field.number != 3) continue
                val offset = number(3) ?: 0; val size = minOf(checkNotNull(number(4)), content.size - offset)
                require(offset in 0 until content.size.toLong() && size in 1..60000)
                val chunk = content.copyOfRange(offset.toInt(), (offset + size).toInt())
                val entry = WireFields.string(1, taskId) + WireFields.integer(2, number(2) ?: 0) + WireFields.integer(3, offset) + WireFields.integer(4, size) + WireFields.bytes(5, chunk)
                requests.sendRaw(ProtocolCodec.frame(17, 0, WireFields.bytes(4, entry)))
            }
            accepted(2, id)
            Thread.sleep(350) // Firmware startup transition, measured on 1.0.5.12.
            var checksum = 0x811c9dc5u
            for (byte in content) checksum = (checksum xor (byte.toUInt() and 255u)) * 0x01000193u
            send(3, JSONObject(settings.toString()).put("action", 1).put("did", id).put("total", content.size)
                .put("pageOffset", 0).put("highLightOffset", 0).put("code", 1).put("checksum", checksum.toString(16).padStart(8, '0')))
            accepted(3, id)
            state = state.copy(active = id, paused = false)
            return JSONObject().put("did", id).put("accepted", true).put("message", "안경이 프롬프터 재생을 확인함")
        } finally { task = null }
    }
    private fun accepted(type: Int, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            check(!disconnected) { "안경 연결이 끊김" }
            val remaining = deadline - System.nanoTime(); check(remaining > 0) { "안경이 프롬프터 요청을 확인하지 않음$type" }
            val (received, body) = checkNotNull(messages.poll(remaining, TimeUnit.NANOSECONDS)) { "프롬프터 확인 시간 초과" }
            if (received != type || body.optString("did") != id) continue
            check(body.optInt("code") == 1 || type == 2 && body.optInt("code") == 7) { "안경이 프롬프터를 거부함: ${body.optInt("code")}" }; return
        }
    }
    fun control(action: String, completion: (Result<JSONObject>) -> Unit) {
        check(!state.busy) { "프롬프터 전송이 끝날 때까지 기다리세요" }
        val smart = state.smartFollow; val token = followGeneration
        if (smart && action in setOf("pause", "stop")) follow.pause()
        operation({ controlBlocking(action) }) { result ->
            if (smart && token == followGeneration) {
                if (result.getOrNull()?.optBoolean("readBack") == true) {
                    when (action) {
                        "stop" -> { follow.stop(); state = state.copy(smartFollow = false, followStatus = ""); changed() }
                        "resume" -> follow.resume()
                    }
                } else follow.suspend("제어 재조회 실패: 따라 읽기 중지. 상태를 확인하세요")
            }
            completion(result)
        }
    }
    fun suspendFollow(reason: String) { if (state.smartFollow) follow.suspend(reason) }
    private fun controlBlocking(action: String): JSONObject {
        val id = checkNotNull(state.active) { "이 앱이 시작한 프롬프터가 없음" }
        val type = when (action) { "pause" -> 4; "resume" -> 5; "stop" -> 6; else -> error("프롬프터 동작은 다음 중 하나여야 함: pause/resume/stop") }
        messages.clear()
        send(type, JSONObject().put("action", 1).put("did", id).apply { if (type == 4) put("offset", if (state.smartFollow) state.followOffset else 0).put("code", 1).put("isCompleted", false) })
        val actual = checkNotNull(requests.request(20, 10, JSONObject().put("action", 1).put("checksum", "").put("state", JSONObject.NULL).put("pageOffset", 0).put("highLightOffset", 0)) { reply, body -> reply == 10 && body.optInt("action") == 2 })
        check(!actual.has("code") || actual.optInt("code") == 1) { "안경이 프롬프터 상태 조회를 받아들이지 않음" }
        val expected = when (action) { "pause" -> 2; "resume" -> 1; else -> 3 }
        val matched = actual.optInt("state") == expected && (expected == 3 || actual.optString("did") == id)
        if (matched) state = state.copy(active = if (expected == 3) null else id, paused = expected == 2)
        return JSONObject().put("did", id).put("readBack", matched).put("deviceState", actual)
            .put("message", if (matched) "프롬프터 상태가 재조회로 확인됨" else "프롬프터 제어를 전송했지만 현재 재조회 값이 요청과 다름")
    }
    private fun send(type: Int, body: JSONObject) = requests.sendRaw(ProtocolCodec.display(20, sequence++ and 65535, type, body.toString(), byteArrayOf()))
    private fun operation(work: () -> JSONObject, completion: (Result<JSONObject>) -> Unit) {
        check(!state.busy) { "프롬프터 전송이 끝날 때까지 기다리세요" }; state = state.copy(busy = true, notice = "안경과 원고 동기화 중…"); changed()
        requests.perform(work) { result -> state = state.copy(busy = false, notice = result.fold({ it.optString("message").ifBlank { "기기에서 확인됨" } }, { it.message ?: "프롬프터 실패" })); changed(); completion(result) }
    }
    fun disconnected() { followGeneration++; follow.stop(); disconnected = true; task = null; transfer.offer(WireFields.Value(6)); messages.offer(0 to JSONObject())
        state = state.copy(active = null, paused = false, smartFollow = false, followStatus = "", notice = "안경 연결이 끊겼습니다. 원고는 휴대폰에 저장되어 있습니다"); changed() }
    fun close() { followGeneration++; follow.close() }
}
