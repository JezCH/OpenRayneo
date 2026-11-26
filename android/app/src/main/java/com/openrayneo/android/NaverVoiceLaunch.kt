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
    private const val ID = 7922
    fun resumed(value: Activity) { activity = WeakReference(value) }
    fun paused(value: Activity) { if (activity?.get() === value) activity = null }
    data class Result(val opened: Boolean, val message: String)
    fun clear(context: Context) {
        pending?.cancel(); pending = null
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun open(context: Context, request: NaverVoiceCommand.Request): Result {
        clear(context)
        val uri = Uri.Builder().scheme("nmap").authority("search")
            .appendQueryParameter("query", request.destination)
            .appendQueryParameter("appname", context.packageName).build()
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
            return Result(true, "네이버지도 검색 요청 · 목적지를 선택하고 ${mode}안내를 시작하세요.")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled())
            return Result(false, "휴대폰 화면을 열고 다시 요청하거나 알림 권한을 허용하세요.")
        return runCatching {
            manager.createNotificationChannel(NotificationChannel("naver_voice", "네이버지도 음성 요청", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel("naver_voice")?.importance == NotificationManager.IMPORTANCE_NONE)
                return Result(false, "네이버지도 음성 요청 알림을 허용하거나 휴대폰에서 다시 요청하세요.")
            val action = PendingIntent.getActivity(context, ID, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
            pending = action
            manager.notify(ID, Notification.Builder(context, "naver_voice")
                .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("네이버지도 검색 요청")
                .setContentText("탭하여 목적지를 확인하고 ${mode}안내를 시작하세요.")
                .setContentIntent(action).setAutoCancel(true).setTimeoutAfter(60_000L)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            Result(false, "휴대폰 알림을 눌러 네이버지도를 여세요. 아직 안내가 시작되지 않았습니다.")
        }.getOrElse { Result(false, "네이버지도 열기 실패 · 휴대폰에서 확인하세요.") }
    }
}
