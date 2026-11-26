// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.lang.ref.WeakReference

/** No invented destination coordinates, private APIs, or background-launch bypass. */
internal object NaverVoiceLaunch {
    private var activity: WeakReference<Activity>? = null
    private var pending: PendingIntent? = null
    private var pendingLaunch: Pair<String, Intent>? = null
    private var pendingAt = 0L
    private var pendingRoute = false
    var onOpened: ((Long) -> Unit)? = null
    private const val ID = 7922
    fun resumed(value: Activity) { activity = WeakReference(value) }
    fun paused(value: Activity) { if (activity?.get() === value) activity = null }
    data class Result(val opened: Boolean, val message: String)
    fun clear(context: Context) {
        pending?.cancel(); pending = null
        pendingLaunch = null; pendingAt = 0L; pendingRoute = false
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun open(context: Context, request: NaverVoiceCommand.Request, place: NaverPlacePolicy.Place? = null): Result {
        clear(context)
        val path = if (request.routeRequested && place != null) NaverPlacePolicy.path(request.mode) else null
        val direct = path != null && place != null
        val builder = Uri.Builder().scheme("nmap")
        if (direct) {
            val parts = checkNotNull(path).split("/")
            builder.authority(parts.first())
            parts.drop(1).forEach { builder.appendPath(it) }
            val destination = checkNotNull(place)
            builder.appendQueryParameter("dlat", destination.latitude.toString())
                .appendQueryParameter("dlng", destination.longitude.toString())
                .appendQueryParameter("dname", destination.name)
        } else builder.authority("search").appendQueryParameter("query", request.destination)
        val uri = builder.appendQueryParameter("appname", context.packageName).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(NavigationFeed.PACKAGE)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        if (intent.resolveActivity(context.packageManager) == null)
            return Result(false, "네이버지도 앱 설치 또는 연결 상태를 확인하세요.")
        val mode = when (request.mode) {
            NaverVoiceCommand.Mode.WALK -> "도보 "
            NaverVoiceCommand.Mode.CAR -> "자동차 "
            NaverVoiceCommand.Mode.PUBLIC -> "대중교통 "
            else -> ""
        }
        val visible = activity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (visible != null && runCatching { visible.startActivity(intent) }.isSuccess)
            return Result(true, if (direct) "네이버지도 ${mode}경로 요청 · 실제 안내 시작은 지도에서 확인하세요."
                else if (request.routeRequested) "네이버지도 검색 요청 · 목적지를 선택하고 ${mode}안내를 시작하세요."
                else "네이버지도 장소 검색을 요청했습니다.")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled())
            return Result(false, "휴대폰 화면을 열고 다시 요청하거나 알림 권한을 허용하세요.")
        return runCatching {
            manager.createNotificationChannel(NotificationChannel("naver_voice", "네이버지도 음성 요청", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel("naver_voice")?.importance == NotificationManager.IMPORTANCE_NONE)
                return Result(false, "네이버지도 음성 요청 알림을 허용하거나 휴대폰에서 다시 요청하세요.")
            val token = java.util.UUID.randomUUID().toString()
            pendingLaunch = token to intent
            pendingAt = android.os.SystemClock.elapsedRealtime()
            pendingRoute = request.routeRequested
            val handoff = Intent(context, NaverVoiceHandoffActivity::class.java).putExtra("token", token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            val action = PendingIntent.getActivity(context, ID, handoff,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
            pending = action
            manager.notify(ID, Notification.Builder(context, "naver_voice")
                .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(if (direct) "네이버지도 경로 요청" else "네이버지도 검색 요청")
                .setContentText(if (request.routeRequested) "탭하여 목적지와 ${mode}경로를 확인하세요." else "탭하여 장소 검색을 여세요.")
                .setContentIntent(action).setAutoCancel(true).setTimeoutAfter(60_000L)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            Result(false, "휴대폰 알림을 눌러 네이버지도를 여세요. 아직 안내가 시작되지 않았습니다.")
        }.getOrElse { clear(context); Result(false, "네이버지도 열기 실패 · 휴대폰에서 확인하세요.") }
    }
    fun consume(activity: Activity, token: String?) {
        val handoff = pendingLaunch ?: return
        if (token != handoff.first || android.os.SystemClock.elapsedRealtime() - pendingAt !in 0L..60_000L) return
        val route = pendingRoute
        val launch = handoff.second
        clear(activity)
        val requestedAt = System.currentTimeMillis()
        if (runCatching { activity.startActivity(launch) }.isSuccess && route) onOpened?.invoke(requestedAt)
    }
}

/** Only the immutable notification action can reach this non-exported activity. */
internal class NaverVoiceHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        NaverVoiceLaunch.consume(this, intent.getStringExtra("token"))
        finish()
    }
}
