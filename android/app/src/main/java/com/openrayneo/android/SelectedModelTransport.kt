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
        val body = JSONObject().put("model", profile.model).put("stream", false).put("messages", messages)
        return Request.Builder().url(MobileModelEndpoint.resolve(profile.url, profile.key))
            .apply { if (profile.key.isNotBlank()) header("Authorization", "Bearer ${profile.key}") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun read(response: Response): Answer {
        check(response.isSuccessful) { "선택 모델 응답 실패 (HTTP ${response.code})" }
        val source = checkNotNull(response.body).source()
        check(!source.request(MAX_RESPONSE_BYTES + 1)) { "AI 응답 길이 초과" }
        val body = JSONObject(source.readUtf8())
        val choice = body.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason") == "stop") { "AI 답변 미완료" }
        return Answer(choice.getJSONObject("message").getString("content").trim().ifBlank { error("빈 AI 답변") },
            body.optString("model").takeIf { it.isNotBlank() })
    }
}
