// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal object AppUpdateInstall {
    const val ACTION = "com.openrayneo.android.validation.UPDATE_RESULT"
    var sessionId by mutableStateOf<Int?>(null); private set
    var confirmation by mutableStateOf<Intent?>(null); private set
    var status by mutableStateOf(""); private set

    suspend fun install(context: Context, candidate: AppUpdate.Candidate, apk: File, idle: () -> Boolean) {
        check(AppUpdate.supported && context.packageName == AppUpdate.PACKAGE)
        check(sessionId == null && idle()) { "진행 중인 기능을 먼저 종료하세요" }
        val pm = context.packageManager
        check(pm.canRequestPackageInstalls()) { "이 앱의 외부 APK 설치 허용이 필요합니다" }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(candidate.size)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        sessionId = id
        status = "설치 준비 중"
        confirmation = null
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                AppUpdate.verifyArchive(context, candidate, apk)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("base.apk", 0, candidate.size).use { out ->
                            val bytes = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(bytes)
                                if (n < 0) break
                                size += n
                                check(size <= candidate.size)
                                digest.update(bytes, 0, n)
                                out.write(bytes, 0, n)
                            }
                            check(size == candidate.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == candidate.digest) { "설치 파일 해시 불일치" }
                            session.fsync(out)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(idle()) { "새 작업이 시작되어 설치를 보류합니다" }
            val callback = PendingIntent.getBroadcast(context, id,
                Intent(context, AppUpdateInstallReceiver::class.java).setAction(ACTION).putExtra("updateSessionId", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            installer.openSession(id).use { session -> session.commit(callback.intentSender); committed = true }
            status = "Android 설치 승인 대기 중"
        } finally {
            if (!committed) {
                runCatching { installer.abandonSession(id) }
                sessionId = null; confirmation = null; status = ""
            }
        }
    }

    fun result(intent: Intent) {
        val id = intent.getIntExtra("updateSessionId", -1)
        if (id != sessionId || intent.action != ACTION) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                status = if (confirmation != null) "Android 설치 확인을 진행하세요" else "설치 확인 정보를 받지 못했습니다. 취소 후 다시 시도하세요."
            }
            PackageInstaller.STATUS_SUCCESS -> { status = "설치 완료 · 앱을 다시 열어 버전을 확인하세요"; sessionId = null; confirmation = null }
            else -> {
                status = "업데이트 설치 실패 또는 취소 · 기존 앱 유지: " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(240)
                sessionId = null; confirmation = null
            }
        }
    }

    fun cancel(context: Context) {
        val id = sessionId ?: return
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            .onSuccess { sessionId = null; confirmation = null; status = "설치 요청 취소" }
            .onFailure { status = "설치 요청을 취소할 수 없습니다. Android 설치 상태를 확인하세요." }
    }
}

class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.packageName == AppUpdate.PACKAGE) AppUpdateInstall.result(intent)
    }
}
