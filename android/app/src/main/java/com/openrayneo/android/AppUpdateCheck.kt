// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Foreground-only metadata checks. No automatic APK download, install or user data upload. */
internal class AppUpdateCheck(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("validation_update_check", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val period = 6 * 60 * 60 * 1000L
    private val retry = 15 * 60 * 1000L
    var automatic by mutableStateOf(prefs.getBoolean("automatic", true)); private set
    var checking by mutableStateOf(false); private set
    var lastCheckedAt by mutableStateOf(prefs.getLong("successAt", 0L)); private set
    var candidate by mutableStateOf(readCandidate()); private set
    var error by mutableStateOf(prefs.getString("error", "").orEmpty()); private set

    private fun readCandidate(): AppUpdate.Candidate? {
        if (!AppUpdate.supported) return null
        return runCatching {
            val json = JSONObject(prefs.getString("candidate", "").orEmpty())
            val code = json.getLong("code")
            val name = json.getString("name")
            val url = json.getString("url")
            val size = json.getLong("size")
            val digest = json.getString("digest")
            val signer = json.getString("signer")
            require(code > BuildConfig.VERSION_CODE && code <= Int.MAX_VALUE &&
                url == "https://github.com/JezCH/OpenRayneo/releases/download/validation-v$code/OpenRayneo-validation.apk" &&
                size in 1..(500L * 1024 * 1024) && Regex("[0-9a-f]{64}").matches(digest) &&
                Regex("[0-9a-f]{64}").matches(signer) && name.length <= 100)
            AppUpdate.Candidate(code, name, json.getString("notes").take(4000), url, size, digest, signer)
        }.getOrNull()
    }

    val summary: String get() = when {
        checking -> "업데이트 확인 중"
        error.isNotBlank() -> "최근 확인 실패 · 다시 확인 가능"
        candidate != null -> "새 버전 " + candidate!!.name
        lastCheckedAt > 0L -> "마지막 확인 시 새 버전 없음"
        else -> "검증 버전 확인 · 다운로드 · 설치"
    }

    fun lastCheckedLabel(): String = if (lastCheckedAt <= 0L) "확인 기록 없음" else
        DateFormat.getDateFormat(app).format(java.util.Date(lastCheckedAt)) + " " +
            DateFormat.getTimeFormat(app).format(java.util.Date(lastCheckedAt))

    fun setAutomatic(enabled: Boolean) {
        automatic = enabled
        prefs.edit().putBoolean("automatic", enabled).apply()
        if (enabled) refresh(false)
    }

    fun refresh(force: Boolean) {
        if (!AppUpdate.supported || job != null || (!force && !automatic)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("attemptAt", 0L)
        val interval = if (error.isBlank() && lastCheckedAt > 0L) period else retry
        if (!force && last > 0L && now >= last && now - last < interval) return
        prefs.edit().putLong("attemptAt", now).apply()
        checking = true
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = AppUpdate.checkLatest(app)
                candidate = result
                error = ""
                lastCheckedAt = System.currentTimeMillis()
                val editor = prefs.edit().putLong("successAt", lastCheckedAt).remove("error")
                if (result == null) editor.remove("candidate") else editor.putString("candidate", JSONObject()
                    .put("code", result.code).put("name", result.name).put("notes", result.notes)
                    .put("url", result.url).put("size", result.size).put("digest", result.digest)
                    .put("signer", result.signer).toString())
                editor.apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.take(300) ?: "업데이트 확인 실패"
                candidate = null
                prefs.edit().putString("error", error).remove("candidate").apply()
            } finally {
                checking = false
                job = null
            }
        }
        job = work
        work.start()
    }

    fun close() { scope.cancel() }
}
