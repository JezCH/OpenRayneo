// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.math.roundToInt
import org.json.JSONObject

internal object NavigationFeed {
    const val PACKAGE = "com.autonavi.minimap"
    val koreanSources = linkedMapOf("com.nhn.android.nmap" to "네이버지도", "net.daum.android.map" to "카카오맵",
        "com.locnall.KimGiSa" to "카카오내비", "com.skt.tmap.ku" to "티맵")
    const val GOOGLE = "com.google.android.apps.maps"
    val sources = linkedMapOf<String,String>().apply {
        putAll(koreanSources)
        put(GOOGLE, "Google 지도")
        put(PACKAGE, "Amap")
    }
    fun isNavigationSource(pkg: String) = pkg in sources
    fun sourceLabel(pkg: String) = sources[pkg] ?: "지도 앱"
    enum class Observation { NON_NAVIGATION, UNRECOGNIZED, RECOGNIZED }
    data class SourceEvidence(val packageName: String, val observed: Int, val recognized: Int, val unrecognized: Int)
    // Active notification metadata only; never retain rejected notification content.
    private val observations = linkedMapOf<String, Pair<String, Observation>>()
    fun observe(key: String, pkg: String, result: Observation) {
        observations.remove(key)
        observations[key] = pkg to result
        while (observations.size > 64) observations.remove(observations.keys.first())
    }
    fun resetObservations() = observations.clear()
    val evidence get() = sources.keys.map { pkg ->
        val seen = observations.values.filter { it.first == pkg }
        SourceEvidence(pkg, seen.size, seen.count { it.second == Observation.RECOGNIZED },
            seen.count { it.second == Observation.UNRECOGNIZED })
    }

    data class Entry(
        val key: String,
        val title: String,
        val texts: List<String>,
        val updatedAt: Long,
        val channel: String,
        val packageName: String = PACKAGE,
    ) {
        val instruction
            get() = if (packageName == PACKAGE) GaodeNotificationParser.parse(texts)
            else KoreanNavigationText.parse(texts)?.let { GaodeNotificationParser.Instruction(it.direction, it.meters) }
        val sourceName get() = sourceLabel(packageName)
        val experimental get() = packageName != PACKAGE
        // Domestic notifications have no confirmed cadence. Expire rather than displaying stale turns.
        val fresh get() = !experimental || (System.currentTimeMillis() - updatedAt) in 0..15_000L
        val safeInstruction get() = instruction?.takeIf { fresh }
    }

    val entries = linkedMapOf<String, Entry>()
    val observers = mutableSetOf<() -> Unit>()
    private var preferences: android.content.SharedPreferences? = null
    var listenerState = NotificationReadState()
        private set

    var lastSnapshotAt = 0L
        private set

    val connected
        get() = listenerState.phase == NotificationReadPhase.CONNECTED

    var selectedKey: String? = null
        private set

    var enabled = false
        private set

    val selected
        get() = if (enabled) entries[selectedKey] else null

    val status: String
        get() =
            when {
                !connected -> listenerState.detail
                !enabled -> "지도 앱에서 길 안내를 시작한 뒤 알림을 직접 선택하세요"
                selected == null -> "선택한 길 안내 알림 대기 중. 알림이 바뀌었다면 다시 선택하세요"
                selected?.fresh == false -> "지도 알림 갱신 대기 · 휴대폰 안내 확인"
                selected?.safeInstruction == null -> "회전 방향과 거리를 인식하지 못했습니다. 휴대폰 안내를 확인하세요"
                else -> selected!!.safeInstruction!!.let { "${selected!!.sourceName} 추종: ${it.direction} · ${it.meters}m" }
            }

    val frame: String?
        get() {
            if (!enabled) return null
            if (!connected) return NavigationFrame.status("알림 읽기 끊김", "휴대폰 확인")
            val entry = selected ?: return NavigationFrame.status("길 안내 알림 대기", "휴대폰 안내 기준")
            return entry.safeInstruction?.let {
                NavigationFrame.render(it.direction, it.meters, if (entry.experimental) entry.sourceName else entry.title)
            } ?: NavigationFrame.status("안내 갱신 대기", "휴대폰 확인")
        }

    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.getSharedPreferences("navigation_notification", Context.MODE_PRIVATE)
        selectedKey = preferences!!.getString("key", null)
        enabled = false
        preferences!!.edit().putBoolean("enabled", false).remove("automaticSelection").apply()
    }

    fun connection(value: NotificationReadState) {
        listenerState = value
        if (!connected) { entries.clear(); resetObservations() }
        changed()
    }

    fun replace(snapshot: List<Entry>, state: NotificationReadState) {
        listenerState = state
        lastSnapshotAt = System.currentTimeMillis()
        entries.clear()
        snapshot.forEach { entries[it.key] = it }
        changed()
    }

    fun update(entry: Entry) {
        entries[entry.key] = entry
        changed()
    }

    fun remove(key: String) {
        val entry = entries.remove(key)
        val observation = observations.remove(key)
        if (entry != null || observation != null) changed()
    }
    fun reject(key: String) {
        entries.remove(key)
        changed()
    }

    fun follow(key: String) {
        val entry = checkNotNull(entries[key]) { "해당 알림이 사라졌습니다. 지도 앱에서 길 안내를 시작하세요" }
        check(entry.safeInstruction != null) { "최신 방향·거리를 인식하지 못했습니다." }
        selectedKey = key
        enabled = true
        preferences
            ?.edit()
            ?.putString("key", key)
            ?.putBoolean("enabled", true)
            ?.apply()
        changed()
    }

    fun stop() {
        enabled = false
        preferences
            ?.edit()
            ?.putBoolean("enabled", false)
            ?.apply()
        changed()
    }

    private fun changed() = observers.toList().forEach { it() }
}

internal class NavigationNotifications : NotificationListenerService() {
    private val reader =
        object : NavigationNotificationReader {
            override fun snapshot(): List<NavigationFeed.Entry> {
                val active = checkNotNull(activeNotifications) { "알림 스냅샷을 사용할 수 없음" }
                NavigationFeed.resetObservations()
                return active.filter { NavigationFeed.isNavigationSource(it.packageName) }.mapNotNull(::parseSafely)
            }

            override fun unbind() = requestUnbind()
        }

    override fun onCreate() {
        super.onCreate()
        NavigationFeed.initialize(this)
        initialize(this)
    }

    override fun onListenerConnected() {
        recovery!!.connected(reader)
    }

    override fun onListenerDisconnected() {
        recovery!!.disconnected(reader)
        PhoneNotificationForwarding.reset()
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (recovery?.owns(reader) != true) return
        if (!NavigationFeed.isNavigationSource(notification.packageName)) {
            PhoneNotificationForwarding.onPosted(this, notification)
            return
        }
        if (notification.packageName != NavigationFeed.PACKAGE && notification.notification.category != Notification.CATEGORY_NAVIGATION) {
            PhoneNotificationForwarding.onPosted(this, notification)
            NavigationFeed.observe(notification.key, notification.packageName, NavigationFeed.Observation.NON_NAVIGATION)
            NavigationFeed.reject(notification.key)
            return
        }
        val entry = parseSafely(notification)
        if (entry != null) NavigationFeed.update(entry) else NavigationFeed.reject(notification.key)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        if (recovery?.owns(reader) != true) return
        if (NavigationFeed.isNavigationSource(notification.packageName)) {
            NavigationFeed.remove(notification.key)
            PhoneNotificationForwarding.removed(notification.key)
        }
        else PhoneNotificationForwarding.removed(notification.key)
    }

    private fun parseSafely(item: StatusBarNotification): NavigationFeed.Entry? =
        try {
            parse(item)
        } catch (failure: Exception) {
            NavigationFeed.observe(item.key, item.packageName, NavigationFeed.Observation.UNRECOGNIZED)
            Log.w("OpenRayneo", "길 안내 알림 파싱 실패: ${failure.javaClass.simpleName}")
            null
        }

    private fun parse(item: StatusBarNotification): NavigationFeed.Entry? {
        val notification = item.notification
        val eligible = notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            (notification.category == Notification.CATEGORY_NAVIGATION ||
                item.packageName == NavigationFeed.PACKAGE && notification.channelId == "ROUTE_CHANNEL_ID")
        if (!eligible) {
            NavigationFeed.observe(item.key, item.packageName, NavigationFeed.Observation.NON_NAVIGATION)
            return null
        }
        val extras = notification.extras ?: Bundle.EMPTY
        val texts =
            buildList {
                    for (key in
                        listOf(
                            Notification.EXTRA_TITLE,
                            Notification.EXTRA_TEXT,
                            Notification.EXTRA_BIG_TEXT,
                            Notification.EXTRA_SUB_TEXT,
                            Notification.EXTRA_SUMMARY_TEXT,
                        )) {
                        extras
                            .getCharSequence(key)
                            ?.toString()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { value ->
                        value?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                    runCatching { extras.getString("miui.focus.param")?.let(::JSONObject) }
                        .getOrNull()
                        ?.let { focus ->
                            for (key in listOf("title", "content")) focus
                                .optString(key)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                }
                .distinct()
        val recognized = if (item.packageName == NavigationFeed.PACKAGE) GaodeNotificationParser.parse(texts) != null
            else KoreanNavigationText.parse(texts) != null
        NavigationFeed.observe(item.key, item.packageName, if (recognized) NavigationFeed.Observation.RECOGNIZED else NavigationFeed.Observation.UNRECOGNIZED)
        if (!recognized) return null
        return NavigationFeed.Entry(
            item.key,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            texts,
            item.postTime,
            notification.channelId.orEmpty(),
            item.packageName,
        )
    }

    override fun onDestroy() {
        recovery?.disconnected(reader)
        super.onDestroy()
    }

    companion object {
        private var recovery: NotificationRecovery? = null
        private val main = Handler(Looper.getMainLooper())

        private fun initialize(context: Context) {
            if (recovery != null) return
            val application = context.applicationContext
            val component = ComponentName(application, NavigationNotifications::class.java)
            recovery =
                NotificationRecovery(
                    authorized = {
                        application
                            .getSystemService(NotificationManager::class.java)
                            .isNotificationListenerAccessGranted(component)
                    },
                    bind = { requestRebind(component) },
                    resetBinding = { if (Build.VERSION.SDK_INT >= 34) requestUnbind(component) },
                    schedule = { task, delay -> main.postDelayed(task, delay) },
                    cancel = { main.removeCallbacks(it) },
                    changed = NavigationFeed::connection,
                    replace = NavigationFeed::replace,
                    log = { Log.i("OpenRayneo", it) },
                )
        }

        /**
         * App foreground entry and background health checks resnapshot even if already connected.
         */
        fun refresh(context: Context, foreground: Boolean = false) {
            initialize(context)
            recovery!!.refresh(manual = foreground)
        }

        /** User-requested recovery keeps authorization and the selected notification unchanged. */
        fun requestConnection(context: Context) {
            initialize(context)
            recovery!!.refresh(manual = true, restart = true)
        }
    }
}

internal object GaodeNotificationParser {
    data class Instruction(val direction: String, val meters: Int)

    private val actions =
        linkedMapOf(
            "向左前方行驶" to "좌전방",
            "向右前方行驶" to "우전방",
            "向左后方行驶" to "좌후방",
            "向右后方行驶" to "우후방",
            "左前方转弯" to "좌전방",
            "右前方转弯" to "우전방",
            "向左转" to "좌회전",
            "向右转" to "우회전",
            "向左前方" to "좌전방",
            "向右前方" to "우전방",
            "直行" to "직진",
            "左转" to "좌회전",
            "右转" to "우회전",
            "掉头" to "유턴",
        )
    private val action = actions.keys.joinToString("|") { Regex.escape(it) }
    private const val number = "([0-9]+(?:\\.[0-9]+)?)\\s*(公里|千米|米|km|m)"
    // Observed real notification: android.text = "直行314米｜高德导航中".
    private val actionFirst = Regex("^($action)\\s*$number(?:[｜|\\s]|$)", RegexOption.IGNORE_CASE)
    private val distanceFirst =
        Regex(
            "^(?:前方\\s*)?$number\\s*(?:后|处)?\\s*($action)(?:[，,。｜|\\s]|进入|驶入|$)",
            RegexOption.IGNORE_CASE,
        )

    fun parse(texts: List<String>): Instruction? {
        for (text in texts) {
            val normalized = text.trim()
            val first = actionFirst.find(normalized)
            if (first != null)
                return instruction(first.groupValues[1], first.groupValues[2], first.groupValues[3])
            val second = distanceFirst.find(normalized)
            if (second != null)
                return instruction(
                    second.groupValues[3],
                    second.groupValues[1],
                    second.groupValues[2],
                )
        }
        return KoreanNavigationText.parse(texts)?.let { Instruction(it.direction, it.meters) }
    }

    private fun instruction(action: String, value: String, unit: String): Instruction? {
        val distance = value.toDoubleOrNull() ?: return null
        val meters = distance * if (unit.lowercase() in setOf("公里", "千米", "km")) 1000 else 1
        if (!meters.isFinite() || meters !in 0.0..9999.0) return null
        return Instruction(actions.getValue(action), meters.roundToInt())
    }
}
