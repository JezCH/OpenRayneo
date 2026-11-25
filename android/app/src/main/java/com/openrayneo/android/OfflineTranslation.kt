// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/** A separately configured phone-local translation runtime. No DNS, remote hosts, proxies or fallback. */
internal class OfflineTranslation(private val main: Handler, private val changed: () -> Unit) {
    private data class Config(val url: String, val model: String, val key: String)
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private var configured: Config? = null
    private var activeCall: Call? = null
    private var epoch = 0L
    private var closed = false
    val ready get() = configured != null
    var preparing = false; private set
    var status = "휴대폰 로컬 번역 모델 연결 필요"; private set

    private fun config(settings: JSONObject): Config {
        val address = settings.optString("offlineTranslationUrl").trim().trimEnd('/')
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/v1") { "주소는 http://127.0.0.1:포트/v1 형식만 허용됩니다" }
        val model = settings.optString("offlineTranslationModel").trim()
        require(model.isNotBlank() && model.length <= 256) { "휴대폰 번역 모델 이름을 입력하세요" }
        return Config("$address/chat/completions", model, settings.optString("offlineTranslationKey"))
    }
    fun readyFor(settings: JSONObject): Boolean = ready && configured == runCatching { config(settings) }.getOrNull()
    fun prepare(settings: JSONObject) {
        check(!closed && !preparing)
        val requested = config(settings)
        cancel()
        configured = null; preparing = true; status = "휴대폰 로컬 번역 모델 시험 중"; changed()
        request(requested, "안녕하세요.", "English") { result ->
            preparing = false
            if (result.isSuccess) { configured = requested; status = "로컬 모델 응답 확인됨 · 오프라인 여부는 기기에서 확인" }
            else { configured = null; status = "로컬 번역 연결 실패 · 서버와 모델을 확인하세요" }
            changed()
        }
    }
    fun translate(text: String, target: String, result: (Result<String>) -> Unit) {
        val cfg = configured
        if (cfg == null || closed) { result(Result.failure(IllegalStateException("로컬 번역 연결 필요"))); return }
        request(cfg, text.take(500), target, result)
    }
    private fun request(cfg: Config, text: String, target: String, result: (Result<String>) -> Unit) {
        activeCall?.cancel()
        val token = ++epoch
        val body = JSONObject().put("model", cfg.model).put("stream", false).put("max_tokens", 160).put("temperature", 0.1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                "Translate the following text into $target. Output only the translation, without any explanation.\\n\\n$text")))
        val call = http.newCall(Request.Builder().url(cfg.url).apply {
            if (cfg.key.isNotBlank()) header("Authorization", "Bearer ${cfg.key}")
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build())
        activeCall = call
        fun finish(value: Result<String>) { main.post {
            if (closed || token != epoch) return@post
            activeCall = null
            result(value)
        } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { finish(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val value = runCatching { response.use {
                    check(it.isSuccessful)
                    val source = checkNotNull(it.body).source()
                    source.request(256 * 1024L + 1)
                    check(source.buffer.size <= 256 * 1024L) { "번역 응답 길이 초과" }
                    JSONObject(source.readUtf8()).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim().ifBlank { error("빈 번역 결과") }
                } }
                finish(value)
            }
        })
    }
    fun cancel() {
        epoch++; activeCall?.cancel(); activeCall = null
        if (preparing) { preparing = false; status = "로컬 번역 준비 취소"; changed() }
    }
    fun close() { closed = true; cancel(); configured = null; http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
