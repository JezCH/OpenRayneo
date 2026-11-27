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

    fun request(profile: AiModelSelection.Profile, messages: JSONArray): Request {
        if (profile.id == AiModelSelection.CHATGPT_PLAN) {
            require(profile.url == AiModelSelection.GPT_URL && profile.key.isEmpty())
            val body = subscriptionBody(profile.model, messages)
            return Request.Builder().url(AiModelSelection.GPT_URL + "/responses")
                .header("Authorization", "Bearer " + ChatGptSubscription.accessFor(profile.model))
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        }
        val body = JSONObject().put("model", profile.model).put("stream", false).put("messages", messages)
        return Request.Builder().url(MobileModelEndpoint.resolve(profile.url, profile.key))
            .apply { if (profile.key.isNotBlank()) header("Authorization", "Bearer ${profile.key}") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun read(response: Response): Answer {
        if (response.request.url.toString() == AiModelSelection.GPT_URL + "/responses") {
            check(response.isSuccessful) { "ChatGPT 구독 응답 실패 (HTTP ${response.code})" }
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
    fun readSubscription(source: okio.BufferedSource): Answer {
        var remaining = MAX_RESPONSE_BYTES
        val data = StringBuilder()
        val delta = StringBuilder()
        fun finishEvent(): Answer? {
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
                    val output = response.optJSONArray("output") ?: JSONArray()
                    val texts = mutableListOf<String>()
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") != "message") continue
                        val parts = item.optJSONArray("content") ?: continue
                        for (j in 0 until parts.length()) {
                            val part = parts.optJSONObject(j) ?: continue
                            if (part.optString("type") == "output_text") texts.add(part.optString("text"))
                        }
                    }
                    val answer = (if (texts.isNotEmpty()) texts.joinToString("\n") else delta.toString()).trim()
                    check(answer.isNotBlank()) { "빈 ChatGPT 구독 응답" }
                    Answer(answer, response.optString("model").takeIf { it.isNotBlank() })
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
