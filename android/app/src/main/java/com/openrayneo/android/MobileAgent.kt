// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** The companion exposes tools and facts; the phone's external Agent owns all response semantics. */
internal class MobileAgent(private val settings: AppSettings, private val repository: ConversationRepository,
    private val restored: (String, String, JSONObject) -> Unit, private val changed: () -> Unit,
    private val tool: (String, JSONObject, () -> Boolean, (Result<JSONObject>) -> Unit) -> Unit,
    private val finalAnswer: (String, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var requestId: String? = null
    var running = false; private set
    var status = "로컬 Agent 엔드포인트를 켜고 DSH Go를 연결하면 사용할 수 있습니다"; private set
    var answer = ""; private set
    var question = ""; private set
    var ephemeral = false; private set
    val harness = DshRelay(main, repository, restored) { message -> if (running) { status = message; changed() } }
    fun submit(text: String, context: String = "", allowTools: Boolean = true, retainHistory: Boolean = true, forceDsh: Boolean = true) {
        check(!running) { "답변을 기다리거나 먼저 중지하세요" }
        val session = ++generation
        question = text; answer = ""; running = true; ephemeral = !retainHistory
        status = "로컬 외부 Agent의 수신 대기 중…"; changed()
        requestId = harness.submit(text, context) { result ->
            if (session == generation) {
                requestId = null; running = false
                result.fold({ reply -> answer = reply; status = "로컬 Agent의 이번 라운드가 끝남"; changed(); finalAnswer(text, reply) },
                    { error -> status = error.message ?: "로컬 Agent 작업이 완료되지 않음"; changed() })
            }
        }
    }
    fun cancel() { generation++; requestId?.let(harness::cancelRequest); requestId = null; running = false; status = "중지됨. 이미 실행된 작업은 유지됨"; changed() }
    fun clear() { cancel(); question = ""; answer = ""; changed() }
    fun close() { generation++; running = false }
}
