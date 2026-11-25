// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/** Only live notifications, explicit per-app opt-in. No content database, AI route or replay queue. */
internal object PhoneNotificationForwarding {
    private fun prefs(context: Context) = context.getSharedPreferences("phone_notification_forwarding", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun allowed(context: Context): Set<String> = prefs(context).getStringSet("packages", emptySet()).orEmpty().toSet()
    fun lockedMetadata(context: Context) = prefs(context).getBoolean("lockedMetadata", false)
    fun setLockedMetadata(context: Context, value: Boolean) { prefs(context).edit().putBoolean("lockedMetadata", value).apply(); reset() }
    fun previews(context: Context): Set<String> = prefs(context).getStringSet("previews", emptySet()).orEmpty().toSet()
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply(); reset() }
    fun setAllowed(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet("packages", packages).putStringSet("previews", previews(context).intersect(packages)).apply()
        reset()
    }
    fun setPreview(context: Context, pkg: String, value: Boolean) {
        if (pkg !in allowed(context)) return
        val previous = previews(context)
        prefs(context).edit().putStringSet("previews", if (value) previous + pkg else previous - pkg).apply()
        reset()
    }

    data class Preview(val title: String, val body: String)
    var deliver: ((Preview) -> Boolean)? = null
    private val recent = linkedMapOf<String, Pair<Int, Long>>()
    private var lastSent: Long? = null
    private const val MIN_INTERVAL = 3_000L
    private const val REPEAT_WINDOW = 60_000L
    private val sensitive = Regex("인증|인증번호|보안코드|일회용|비밀번호|verification|passcode|password|one.time|\\botp\\b|security code|sign.in code", RegexOption.IGNORE_CASE)

    fun reset() { recent.clear(); lastSent = null }
    fun removed(key: String) { recent.remove(key) }
    fun onPosted(context: Context, item: StatusBarNotification) {
        val sink = deliver ?: return
        val pkg = item.packageName
        if (!enabled(context) || pkg !in allowed(context) || pkg.startsWith("com.openrayneo.") || pkg == NavigationFeed.PACKAGE) return
        if (item.user != android.os.Process.myUserHandle()) return
        val notification = item.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            notification.visibility == Notification.VISIBILITY_SECRET ||
            notification.category == Notification.CATEGORY_NAVIGATION ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 && notification.category != Notification.CATEGORY_CALL)) return
        val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive
        if (!unlocked && !lockedMetadata(context)) return
        val now = SystemClock.elapsedRealtime()
        val previousSend = lastSent
        if (previousSend != null && now - previousSend < MIN_INTERVAL) return
        val appName = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val message = if (unlocked && pkg in previews(context) && notification.category != Notification.CATEGORY_CALL) {
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (sensitive.containsMatchIn(title + " " + body)) "새 알림"
            else listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "새 알림" }
        } else "새 알림"
        val fingerprint = if (message == "새 알림") item.postTime.hashCode() else message.hashCode()
        val prior = recent[item.key]
        if (prior != null && prior.first == fingerprint && now - prior.second < REPEAT_WINDOW) return
        // Bound metadata and payload; do not retain notification text, even on failure.
        val sent = sink(Preview(appName.take(32), message.replace(Regex("[\\r\\n\\t]+"), " ").take(100)))
        if (!sent) return
        lastSent = now
        recent[item.key] = fingerprint to now
        while (recent.size > 64) recent.remove(recent.keys.first())
    }
}
