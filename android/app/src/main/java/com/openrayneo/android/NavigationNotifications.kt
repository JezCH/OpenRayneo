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
 

internal object NavigationFeed {
    const val PACKAGE = "com.nhn.android.nmap"
    const val SOURCE_NAME = "네이버지도"
    val sources = mapOf(PACKAGE to SOURCE_NAME)
    fun isNavigationSource(pkg: String) = pkg == PACKAGE
    fun sourceLabel(pkg: String) = sources[pkg] ?: "지원하지 않는 지도 앱"
    // A lifecycle-only notification does not supply a maneuver, route ID or map.
    enum class Observation { NON_NAVIGATION, STATUS_ONLY, WALK_STEP, UNRECOGNIZED, RECOGNIZED }
    data class SourceEvidence(val packageName: String, val observed: Int, val recognized: Int, val unrecognized: Int,
                              val walkSteps: Int = 0, val nowBarSteps: Int = 0,
                              val nowBarProgress: Int? = null)
    data class NativeWalkStep(val instruction: KoreanNavigationText.Instruction, val postedAt: Long,
                              val nowBar: Boolean = false, val progressPercent: Int? = null) {
        fun freshAt(now: Long) = now >= postedAt && now - postedAt < NativePhoneGuidance.VALID_FOR_MS
    }
    // Partial source data, never a native map or route identity. The existing
    // explicitly selected arrow HUD may consume the exact same observation.
    private val nativeWalkSteps = linkedMapOf<String, NativeWalkStep>()
    fun nativeWalkStep(now: Long): NativeWalkStep? =
        if (!connected || activeNavigationCount != 1) null
        else nativeWalkSteps.values.singleOrNull()?.takeIf { it.freshAt(now) }
    // Active notification metadata only; never retain rejected notification content.
    private val observations = linkedMapOf<String, Pair<String, Observation>>()
    fun observe(key: String, pkg: String, result: Observation) {
        observations.remove(key)
        if (result != Observation.WALK_STEP) nativeWalkSteps.remove(key)
        observations[key] = pkg to result
        while (observations.size > 64) {
            val oldest = observations.keys.first()
            observations.remove(oldest)
            nativeWalkSteps.remove(oldest)
        }
    }
    fun resetObservations() { observations.clear(); nativeWalkSteps.clear() }
    fun observeWalk(key: String, pkg: String, title: String, text: String, postedAt: Long,
                    cardTitle: String? = null, cardDistance: String? = null,
                    progress: Int? = null, progressMax: Int? = null): NativeWalkStep? {
        if (!isNavigationSource(pkg)) return null
        // Original stock resources: terminal titles (do not mark navigation active).
        if (title.trim() in setOf("길안내를 종료합니다.", "목적지에 도착했습니다.", "End directions.", "You've arrived at your destination.")) {
            observe(key, pkg, Observation.NON_NAVIGATION)
            return null
        }
        val standard = KoreanNavigationText.parseNaverWalk(title, text)
        val card = if (cardTitle != null && cardDistance != null)
            KoreanNavigationText.parseNaverNowBar(cardTitle, cardDistance) else null
        // The two observations must agree if both are available. Do not infer
        // a maneuver from a road name or an arbitrary manufacturer extra.
        val step = when {
            standard != null && card != null && standard != card -> null
            else -> card ?: standard
        }
        if (step != null && postedAt > 0) {
            val percent = if (card != null && progressMax == 100 && progress != null &&
                progress in 0..100) progress else null
            val value = NativeWalkStep(step, postedAt, card != null, percent)
            nativeWalkSteps[key] = value
            observe(key, pkg, Observation.WALK_STEP)
            return value
        }
        observe(key, pkg, Observation.STATUS_ONLY)
        return null
    }
    // Explicit route-producer binding only: a single fresh, matching walking
    // notification may coexist with native route ownership. No generic exemption.
    fun matchesNativeWalkSource(key: String, now: Long): Boolean =
        connected && activeNavigationCount == 1 &&
            observations[key] == (PACKAGE to Observation.WALK_STEP) &&
            nativeWalkSteps[key]?.freshAt(now) == true
    val activeNavigationCount get() = observations.values.count { it.second != Observation.NON_NAVIGATION }
    fun activeNavigationKey(key: String) = observations[key]?.let { (pkg, observation) ->
        isNavigationSource(pkg) && observation != Observation.NON_NAVIGATION
    } == true
    val evidence get() = sources.keys.map { pkg ->
        val seen = observations.values.filter { it.first == pkg }
        SourceEvidence(pkg, seen.size, seen.count { it.second == Observation.RECOGNIZED },
            seen.count { it.second == Observation.UNRECOGNIZED || it.second == Observation.STATUS_ONLY },
            seen.count { it.second == Observation.WALK_STEP },
            if (pkg == PACKAGE) nativeWalkSteps.values.count { it.nowBar } else 0,
            if (pkg == PACKAGE) nativeWalkStep(System.currentTimeMillis())?.progressPercent else null)
    }

    data class Entry(
        val key: String,
        val title: String,
        val texts: List<String>,
        val updatedAt: Long,
        val channel: String,
        val packageName: String = PACKAGE,
        val walkStep: NativeWalkStep? = null,
    ) {
        val instruction = if (!isNavigationSource(packageName)) null
            else if (walkStep != null) walkStep.instruction.takeIf {
                channel == "350_WALK_NAVIGATION" && updatedAt == walkStep.postedAt
            } else KoreanNavigationText.parse(texts)
        val sourceName get() = sourceLabel(packageName)
        // No provider exemption: never extend an old turn's lifetime by resnapshotting it.
        fun freshAt(now: Long) = if (walkStep != null) walkStep.freshAt(now)
            else (now - updatedAt) in 0..15_000L
        val fresh get() = freshAt(System.currentTimeMillis())
        val safeInstruction get() = instruction?.takeIf {
            fresh && (walkStep == null || (connected && activeNavigationCount == 1 &&
                nativeWalkSteps[key] == walkStep))
        }
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
        get() {
            if (!connected) return listenerState.detail
            if (!enabled) return "네이버지도에서 길 안내를 시작한 뒤 안경 안내를 시작하세요"
            val entry = selected ?: return "선택한 길 안내 알림 대기 중. 알림이 바뀌었다면 다시 선택하세요"
            // Evaluate freshness exactly once: it can expire between two wall-clock reads.
            val instruction = entry.safeInstruction ?: return if (entry.instruction == null)
                "회전 방향과 거리를 인식하지 못했습니다. 휴대폰 안내를 확인하세요"
            else "지도 알림 갱신 대기 · 휴대폰 안내 확인"
            return "${entry.sourceName} 추종: ${instruction.direction} · ${instruction.meters}m"
        }

    val frame: String?
        get() {
            if (!enabled) return null
            if (!connected) return NavigationFrame.status("알림 읽기 끊김", "휴대폰 확인")
            val entry = selected ?: return NavigationFrame.status("길 안내 알림 대기", "휴대폰 안내 기준")
            return entry.safeInstruction?.let {
                NavigationFrame.render(it.direction, it.meters, SOURCE_NAME)
            } ?: NavigationFrame.status("안내 갱신 대기", "휴대폰 확인")
        }

    fun initialize(context: Context) {
        if (preferences != null) return
        preferences = context.getSharedPreferences("navigation_notification", Context.MODE_PRIVATE)
        // Old provider selections must not survive migration; no automatic capture on upgrade.
        selectedKey = null
        enabled = false
        preferences!!.edit().putBoolean("enabled", false).remove("key").remove("automaticSelection").apply()
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
        snapshot.filter { isNavigationSource(it.packageName) }.forEach { entries[it.key] = it }
        changed()
    }

    fun update(entry: Entry) {
        if (!isNavigationSource(entry.packageName)) return
        entries[entry.key] = entry
        changed()
    }

    fun remove(key: String) {
        val entry = entries.remove(key)
        val observation = observations.remove(key)
        nativeWalkSteps.remove(key)
        if (entry != null || observation != null) changed()
    }
    fun reject(key: String) {
        entries.remove(key)
        changed()
    }

    fun follow(key: String) {
        val entry = checkNotNull(entries[key]) { "해당 알림이 사라졌습니다. 지도 앱에서 길 안내를 시작하세요" }
        check(connected && isNavigationSource(entry.packageName) && entry.safeInstruction != null) { "네이버지도의 최신 방향·거리를 인식하지 못했습니다." }
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
        val entry = parseSafely(notification)
        if (entry != null) NavigationFeed.update(entry) else {
            // Normal Naver notifications remain eligible for the existing opt-in phone forwarding.
            if (!NavigationFeed.activeNavigationKey(notification.key) &&
                notification.notification.channelId != "350_WALK_NAVIGATION")
                PhoneNotificationForwarding.onPosted(this, notification)
            NavigationFeed.reject(notification.key)
        }
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
        val eligibleSource = NavigationFeed.isNavigationSource(item.packageName) &&
            notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        if (!eligibleSource) {
            NavigationFeed.observe(item.key, item.packageName, NavigationFeed.Observation.NON_NAVIGATION)
            return null
        }
        val extras = notification.extras ?: Bundle.EMPTY
        // Original NAVER Maps 6.13.1.2 classes18.dex WalkNaviNotificationBuilder
        // a/c uses channel 350_WALK_NAVIGATION and CATEGORY_SERVICE, and
        // WalkNaviNotiUiStateMapper emits title plus remaining next-step distance.
        // Expose only the validated turn and distance to the existing explicit
        // arrow HUD selection. Do not start a native map or infer route geometry.
        if (notification.channelId == "350_WALK_NAVIGATION" && notification.category == Notification.CATEGORY_SERVICE) {
            val step = NavigationFeed.observeWalk(item.key, item.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), item.postTime,
                // Read only the four exact keys emitted by stock APK 6.13.1.2:
                // OngoingActivity.Card.apply 0x3ff8a4 / Expand.Progress.apply 0x400318.
                // No icons, pending intents, coordinates or arbitrary extras are collected.
                extras.getString("android.ongoingActivityNoti.nowbarPrimaryInfo"),
                extras.getString("android.ongoingActivityNoti.nowbarSecondaryInfo"),
                if (extras.containsKey("android.ongoingActivityNoti.progress"))
                    extras.getInt("android.ongoingActivityNoti.progress", -1) else null,
                if (extras.containsKey("android.ongoingActivityNoti.progressMax"))
                    extras.getInt("android.ongoingActivityNoti.progressMax", -1) else null)
            return step?.let {
                NavigationFeed.Entry(item.key, "네이버 도보 방향·거리", emptyList(),
                    item.postTime, notification.channelId, item.packageName, it)
            }
        }
        // Existing NAVER interop evidence: NaviToTesla's NaverPoiFinder observes
        // this exact active-guidance text independently of notification category.
        // It is NOT a direction, a distance, or a complete native route.
        val statusOnly = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ==
            "내비게이션 - 안내 중"
        if (notification.category != Notification.CATEGORY_NAVIGATION) {
            NavigationFeed.observe(item.key, item.packageName, if (statusOnly)
                NavigationFeed.Observation.STATUS_ONLY else NavigationFeed.Observation.NON_NAVIGATION)
            return null
        }
        val texts =
            buildList {
                    for (key in
                        listOf(
                            Notification.EXTRA_TITLE,
                            Notification.EXTRA_TEXT,
                            Notification.EXTRA_BIG_TEXT,
                            Notification.EXTRA_SUB_TEXT,
                            Notification.EXTRA_SUMMARY_TEXT,
                            // Public Android 16 Live Updates short text; no private payloads.
                            "android.shortCriticalText",
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
                    // Standard Android notification fields only; no manufacturer-private payloads.
                }
                .distinct()
        val recognized = KoreanNavigationText.parse(texts) != null
        NavigationFeed.observe(item.key, item.packageName, when {
            recognized -> NavigationFeed.Observation.RECOGNIZED
            statusOnly -> NavigationFeed.Observation.STATUS_ONLY
            else -> NavigationFeed.Observation.UNRECOGNIZED
        })
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

