// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Single transport contract for all app-owned remote text inference.
 * The immutable profile belongs to the caller's selected model. No fallback. */
internal object SelectedModelTransport {
    data class Answer(val text: String, val reportedModel: String?)
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

    fun usesResponses(profile: AiModelSelection.Profile) = profile.id in setOf(AiModelSelection.CHATGPT_PLAN, AiModelSelection.GPT)

    fun responsesRequest(profile: AiModelSelection.Profile, body: JSONObject): Request {
        require(usesResponses(profile) && profile.url == AiModelSelection.GPT_URL && profile.configured)
        val bearer = if (profile.id == AiModelSelection.CHATGPT_PLAN) {
            require(profile.key.isEmpty())
            ChatGptSubscription.accessFor(profile.model)
        } else profile.key.also { require(it.isNotBlank()) }
        return Request.Builder().url(AiModelSelection.GPT_URL + "/responses")
            .header("Authorization", "Bearer " + bearer)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
    fun request(profile: AiModelSelection.Profile, messages: JSONArray): Request {
        if (usesResponses(profile)) return responsesRequest(profile, subscriptionBody(profile.model, messages))
        val body = JSONObject().put("model", profile.model).put("stream", false).put("messages", messages)
        return Request.Builder().url(MobileModelEndpoint.resolve(profile.url, profile.key))
            .apply { if (profile.key.isNotBlank()) header("Authorization", "Bearer ${profile.key}") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun read(response: Response): Answer {
        if (response.request.url.toString() == AiModelSelection.GPT_URL + "/responses") {
            check(response.isSuccessful) { "선택 모델 응답 실패 (HTTP ${response.code})" }
            return readSubscription(checkNotNull(response.body).source())
        }
        check(response.isSuccessful) { "선택 모델 응답 실패 (HTTP ${response.code})" }
        val source = checkNotNull(response.body).source()
        check(!source.request(MAX_RESPONSE_BYTES + 1)) { "AI 응답 길이 초과" }
        val body = JSONObject(source.readUtf8())
        val choice = body.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason") == "stop") { "AI 답변 미완료" }
        return Answer(choice.getJSONObject("message").getString("content").trim().ifBlank { error("빈 AI 답변") },
            body.optString("model").takeIf { it.isNotBlank() })
    }

    /** The subscription route is deliberately isolated from paid Chat Completions.
     * No paid service tier, unsupported parameters, tools, or stored response IDs. */
    fun subscriptionBody(model: String, messages: JSONArray): JSONObject {
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val item = messages.getJSONObject(i)
            val role = item.getString("role").let { if (it == "system") "developer" else it }
            require(role in setOf("developer", "user", "assistant"))
            input.put(JSONObject().put("role", role).put("content", item.getString("content")))
        }
        return JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true)
    }

    /** Accept only a terminal completed event. A partial response must not be
     * displayed as a finished answer or committed to attributed memory. */
    data class Terminal(val response: JSONObject, val delta: String)
    fun readSubscription(source: okio.BufferedSource): Answer {
        val terminal = readResponsesTerminal(source)
        val output = terminal.response.optJSONArray("output") ?: JSONArray()
        check((0 until output.length()).none { output.optJSONObject(it)?.optString("type") == "function_call" }) {
            "질문 전용 모델이 예상하지 않은 도구 호출을 반환했습니다"
        }
        return textAnswer(terminal)
    }
    fun textAnswer(terminal: Terminal): Answer {
        val output = terminal.response.optJSONArray("output") ?: JSONArray()
        val texts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") texts.add(part.optString("text"))
                if (part.optString("type") == "refusal") texts.add(part.optString("refusal"))
            }
        }
        val answer = (if (texts.isNotEmpty()) texts.joinToString("\n") else terminal.delta).trim()
        check(answer.isNotBlank()) { "빈 AI 응답" }
        return Answer(answer, terminal.response.optString("model").takeIf { it.isNotBlank() })
    }
    fun readResponsesTerminal(source: okio.BufferedSource): Terminal {
        var remaining = MAX_RESPONSE_BYTES
        val data = StringBuilder()
        val delta = StringBuilder()
        fun finishEvent(): Terminal? {
            if (data.isEmpty()) return null
            val event = JSONObject(data.toString())
            data.setLength(0)
            return when (event.optString("type")) {
                "response.output_text.delta" -> {
                    delta.append(event.getString("delta")); check(delta.length <= MAX_RESPONSE_BYTES); null
                }
                "response.failed", "response.incomplete", "error" -> {
                    val code = event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                        ?: event.optString("code")
                    error(when (code) {
                        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 구독 사용량 한도 도달 · 추가 결제하지 않습니다"
                        "subscription_sharing_usage_unavailable" -> "현재 계정의 구독 추론을 사용할 수 없습니다"
                        else -> "ChatGPT 구독 응답 미완료"
                    })
                }
                "response.completed" -> {
                    val response = event.getJSONObject("response")
                    check(response.optString("status") == "completed") { "ChatGPT 구독 응답 미완료" }
                    Terminal(response, delta.toString())
                }
                else -> null
            }
        }
        while (!source.exhausted()) {
            check(remaining > 0) { "ChatGPT 구독 응답 길이 초과" }
            val line = source.readUtf8LineStrict(remaining)
            remaining -= line.toByteArray(Charsets.UTF_8).size + 1L
            check(remaining >= 0) { "ChatGPT 구독 응답 길이 초과" }
            if (line.isEmpty()) finishEvent()?.let { return it }
            else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
        error("ChatGPT 구독 스트림이 완료되기 전에 종료됨")
    }
}
