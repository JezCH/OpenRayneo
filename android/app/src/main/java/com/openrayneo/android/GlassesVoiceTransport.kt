// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Native assistant and LifeLog wire ownership. No account login or official cloud relay. */
internal class GlassesVoiceTransport(
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val wake: () -> Unit, private val beginRound: () -> Unit,
    private val endRound: (Boolean) -> Unit, private val audio: (ByteArray) -> Unit,
    /** Per-frame speech flags (vpuMask | vadMask) of an always-on batch, delivered before its audio. */
    private val activity: (List<Boolean>) -> Unit,
    private val failure: (String) -> Unit,
    /** App log for assistant control messages we send (live partials excluded). */
    private val trace: (String) -> Unit = {},
) {
    var source: ConversationSource? = null; private set
    var wakeId: String? = null; private set
    var nativeActive = false; private set
    private var nativeCode = 0
    private var sequence = 0x7100
    private var lifeTask: String? = null
    private var lastReply: String? = null
    private var advancedReply: String? = null
    /** Wake whose final transcript was already sent; later partials would arrive out of order. */
    private var finalSent: String? = null
    private val client = UUID.randomUUID().toString().replace("-", "")
    fun start(next: ConversationSource) {
        check(source == null || source == next) { "먼저 기존 오디오 기능을 종료하세요" }
        source = next
        when (next) {
            ConversationSource.NATIVE -> {
                check(nativeActive && nativeCode in 1..6) { "먼저 깨우기 명령어(Hey RayNeo 등)로 안경을 깨우고 자동 응답을 켜세요" }
                send(0x0d, 2, JSONObject().put("rc", 1)); send(0x0d, 9, JSONObject().put("rc", 1))
            }
            ConversationSource.ALWAYS_ON -> send(0x0f, 20, JSONObject().put("cmd", "life_log_switch")
                .put("payload", JSONObject().put("value", 1).put("mode", 0).put("data", "{\"delay\":2}")))
            ConversationSource.REALTIME -> Unit
        }
    }
    fun stop() {
        val previous = source; source = null; lifeTask = null
        if (previous == ConversationSource.NATIVE) send(0x0d, 2, JSONObject().put("rc", 2))
        if (previous == ConversationSource.ALWAYS_ON) send(0x0f, 20, JSONObject().put("cmd", "life_log_switch")
            .put("payload", JSONObject().put("value", 0).put("mode", 0).put("data", "")))
    }
    fun disconnected() { source = null; nativeActive = false; wakeId = null; lifeTask = null }
    fun receive(frame: ProtocolCodec.Frame) {
        if (frame.protocol == 0x0f) {
            val message = ProtocolCodec.message(frame.payload); val body = message.json?.let(::JSONObject) ?: return
            if (message.type == 21 && source == ConversationSource.ALWAYS_ON && body.optString("cmd") == "life_log_switch_result") {
                val code = body.optJSONObject("payload")?.optInt("value", -1) ?: -1
                if (code != 0) failure("안경이 종일 기록을 거부함: " + when (code) { 1 -> "마이크 권한 꺼짐"; 2 -> "안경 접힘"; 3 -> "배터리 부족"; else -> "기기 코드 $code" })
            }
            return
        }
        if (frame.protocol != 0x0d) return
        val message = ProtocolCodec.message(frame.payload)
        val body = message.json?.let(::JSONObject) ?: JSONObject()
        when (message.type) {
            1 -> {
                nativeActive = true; nativeCode = body.optInt("rc"); wakeId = UUID.randomUUID().toString()
                lastReply = null; advancedReply = null
                if (nativeCode in 1..6) wake()
            }
            3 -> if (source == ConversationSource.NATIVE) message.audio?.let(audio)
            8 -> { nativeActive = false; if (source == ConversationSource.NATIVE) endRound(true) }
            11 -> if (nativeActive && lastReply != null && advancedReply != lastReply) {
                val sid = body.optString("sid")
                if (sid.isNotBlank() && sid != lastReply) return
                advancedReply = lastReply; wakeId = UUID.randomUUID().toString(); wake()
            }
            161 -> if (source == ConversationSource.ALWAYS_ON && lifeTask == null) {
                val id = UUID.randomUUID().toString().lowercase(); lifeTask = id
                beginRound()
                send(0x0d, 162, JSONObject().put("taskId", id).put("idleTimeoutSec", 300))
                send(0x0d, 168, JSONObject().put("inRealtimePage", false))
            }
            163 -> if (source == ConversationSource.ALWAYS_ON && lifeTask != null) {
                val task = body.optString("taskId")
                if (task.isNotBlank() && task != lifeTask) return
                val bytes = message.audio ?: return
                val count = body.optInt("frameCount", bytes.size / 240)
                if (count !in 1..31 || bytes.size != count * 240) { failure("종일 기록 오디오 프레임 길이 이상으로 이번 라운드를 중지함"); return }
                if (body.has("vpuMask") || body.has("vadMask")) {
                    val vpu = body.optLong("vpuMask"); val vad = body.optLong("vadMask")
                    activity(List(count) { ((vpu shr it) and 1L) != 0L || ((vad shr it) and 1L) != 0L })
                }
                repeat(count) { index -> audio(bytes.copyOfRange(index * 240, (index + 1) * 240)) }
            }
            165 -> if (source == ConversationSource.ALWAYS_ON && (body.optString("taskId").isBlank() || body.optString("taskId") == lifeTask)) {
                lifeTask = null; endRound(false)
            }
        }
    }
    fun asr(text: String, expectedWake: String) {
        if (text.isBlank() || !nativeActive || wakeId != expectedWake) return
        finalSent = expectedWake
        send(0x0d, 4, JSONObject().put("rc", 1))
        send(0x0d, 5, JSONObject().put("text", text).put("final", true))
        send(0x0d, 4, JSONObject().put("rc", 2)); send(0x0d, 9, JSONObject().put("rc", 3))
    }
    /** Live, still-changing transcript on the native assistant page, as the official app streams it. */
    fun partial(text: String, expectedWake: String) {
        if (text.isBlank() || !nativeActive || wakeId != expectedWake || finalSent == expectedWake) return
        send(0x0d, 5, JSONObject().put("text", text).put("final", false))
    }
    fun reply(text: String, query: String, expectedWake: String): Boolean {
        if (!nativeActive || wakeId != expectedWake) return false
        send(0x0d, 2, JSONObject().put("rc", 2))
        val body = JSONObject().put("sub", "workflow").put("vendor", "OpenRayneo").put("uuid", client)
            .put("sid", expectedWake.replace("-", "").lowercase()).put("round", -1).put("timestamp", System.currentTimeMillis() / 1000)
            .put("query", query).put("domain", "chat").put("intent", "chat").put("offline", false)
            .put("answer", JSONObject().put("text", text).put("isFinal", true))
        lastReply = expectedWake.replace("-", "").lowercase()
        send(0x0d, 9, JSONObject().put("rc", 3)); send(0x0d, 32, body); send(0x0d, 12, JSONObject())
        return true
    }
    private fun send(protocol: Int, type: Int, body: JSONObject) {
        if (protocol == 0x0d && !(type == 5 && !body.optBoolean("final"))) trace("음성 이벤트 송신 type=$type ${body.toString().take(160)}")
        try { write(ProtocolCodec.display(protocol, sequence++ and 65535, type, body.toString())) { result ->
            result.onFailure { failure("안경 음성 제어 전송 실패: ${it.message}") }
        } } catch (error: Exception) { failure("안경 음성 제어 전송 실패: ${error.message}") }
    }
}
