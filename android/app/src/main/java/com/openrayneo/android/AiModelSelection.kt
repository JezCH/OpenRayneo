// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject

/** One selected provider for app-owned text inference. No implicit cross-provider fallback. */
internal object AiModelSelection {
    const val KEY = "aiModelSelection"
    const val PROFILES = "aiModelProfiles"
    const val LEGACY = "legacy"
    const val GPT = "gpt"
    const val RAYNEO = "rayneo"
    const val GPT_URL = "https://api.openai.com/v1"
    const val GPT_DEFAULT = "gpt-6.1-terra"
    data class Profile(val id: String, val label: String, val url: String, val model: String, val key: String) {
        val configured get() = runCatching { model.isNotBlank() && MobileModelEndpoint.resolve(url, key).isNotBlank() }.getOrDefault(false)
        fun json() = JSONObject().put("id", id).put("label", label).put("url", url).put("model", model).put("key", key)
    }
    fun selected(config: JSONObject): String = config.optString(KEY).ifBlank { LEGACY }
    fun legacy(config: JSONObject) = selected(config) == LEGACY
    fun profiles(config: JSONObject): List<Profile> {
        val a = runCatching { JSONArray(config.optString(PROFILES, "[]")) }.getOrElse { JSONArray() }
        return (0 until minOf(a.length(), 32)).mapNotNull { i ->
            runCatching {
                val row = a.getJSONObject(i)
                val id = row.getString("id")
                require(id == GPT || id.matches(Regex("custom-[a-f0-9-]{36}")))
                val label = if (id == GPT) "GPT" else row.getString("label").trim().take(60)
                val url = if (id == GPT) GPT_URL else row.getString("url").trim()
                val model = row.getString("model").trim().take(256)
                require(label.isNotBlank() && url.length <= 2048)
                Profile(id, label, url, model, row.optString("key"))
            }.getOrNull()
        }.distinctBy { it.id }
    }
    fun direct(config: JSONObject): Profile? = profiles(config).firstOrNull { it.id == selected(config) && it.configured }
    fun label(config: JSONObject): String = when (val id = selected(config)) {
        LEGACY -> "기존 연결 방식"
        GPT -> "GPT"
        RAYNEO -> "RayNeo AI"
        else -> profiles(config).firstOrNull { it.id == id }?.label ?: "모델 연결 필요"
    }
    /** Resolve only at the inference boundary; never mutate stored legacy settings. */
    fun endpoint(config: JSONObject): Profile? = if (legacy(config))
        Profile(LEGACY, "기존 요약 모델", config.optString("modelUrl"), config.optString("modelName"), config.optString("modelKey")).takeIf { it.configured }
        else direct(config)
    fun withProfile(config: JSONObject, profile: Profile): String {
        require(profile.configured && (profile.id == GPT && profile.url == GPT_URL ||
            profile.id.matches(Regex("custom-[a-f0-9-]{36}"))))
        val all = profiles(config).filterNot { it.id == profile.id } + profile
        require(all.size <= 32)
        return JSONArray(all.map { it.json() }).toString()
    }
}
