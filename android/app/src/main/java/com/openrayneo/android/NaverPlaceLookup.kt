// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.text.Html
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Optional, user-provided API HUB credentials. Never in general configuration/export or logs. */
internal object NaverPlaceLookup {
    data class Config(val id: String = "", val secret: String = "",
                      val defaultMode: NaverVoiceCommand.Mode = NaverVoiceCommand.Mode.UNSPECIFIED) {
        val enabled get() = id.isNotBlank() && secret.isNotBlank()
        override fun toString() = "NaverPlaceLookup.Config(enabled=$enabled)"
    }
    private fun settings(context: Context) = AppSettings(context, "naver_place_lookup")
    fun read(context: Context): Config {
        val json = settings(context).read()
        val mode = NaverVoiceCommand.Mode.values().firstOrNull { it.name == json.optString("defaultMode") }
        return Config(json.optString("id"), json.optString("secret"), mode ?: NaverVoiceCommand.Mode.UNSPECIFIED)
    }
    fun save(context: Context, config: Config) {
        require(config.id.length <= 256 && config.secret.length <= 512 &&
            config.id.none(Char::isISOControl) && config.secret.none(Char::isISOControl))
        require((config.id.isBlank() && config.secret.isBlank()) || config.enabled) { "인증 ID와 Secret을 함께 입력하세요." }
        settings(context).save(JSONObject().put("id", config.id.trim()).put("secret", config.secret.trim())
            .put("defaultMode", config.defaultMode.name))
    }
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun search(config: Config, query: String, result: (NaverPlacePolicy.Place?) -> Unit): Call {
        require(config.enabled)
        val url = HttpUrl.Builder().scheme("https").host("naverapihub.apigw.ntruss.com")
            .addPathSegments("search/v1/local").addQueryParameter("query", query)
            .addQueryParameter("display", "5").addQueryParameter("start", "1")
            .addQueryParameter("sort", "random").addQueryParameter("format", "json").build()
        val request = Request.Builder().url(url)
            .header("X-NCP-APIGW-API-KEY-ID", config.id)
            .header("X-NCP-APIGW-API-KEY", config.secret).build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result(null) }
            override fun onResponse(call: Call, response: Response) {
                val resolved = runCatching {
                    response.use { r ->
                        if (!r.isSuccessful) return@use null
                        val body = r.body ?: return@use null
                        val source = body.source()
                        if (source.request(65_537L)) return@use null
                        val data = JSONObject(source.readUtf8())
                        val items = data.optJSONArray("items") ?: return@use null
                        if (data.optInt("total") != 1 || items.length() != 1) return@use null
                        val item = items.optJSONObject(0) ?: return@use null
                        val title = Html.fromHtml(item.optString("title"), Html.FROM_HTML_MODE_LEGACY).toString()
                        NaverPlacePolicy.unique(1, query, title, item.optString("roadAddress"),
                            item.optString("address"), item.optString("mapx"), item.optString("mapy"))
                    }
                }.getOrNull()
                result(resolved)
            }
        })
        return call
    }
}
