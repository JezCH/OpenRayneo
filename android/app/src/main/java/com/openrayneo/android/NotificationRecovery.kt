// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal enum class NotificationReadPhase {
    NOT_GRANTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

internal data class NotificationReadState(
    val phase: NotificationReadPhase = NotificationReadPhase.DISCONNECTED,
    val detail: String = "알림 리스너 서비스 대기 중",
)

internal interface NavigationNotificationReader {
    fun snapshot(): List<NavigationFeed.Entry>

    fun unbind()
}

/** All calls are serialized on the main thread. Timers retry binding, never alter permission. */
internal class NotificationRecovery(
    private val authorized: () -> Boolean,
    private val bind: () -> Unit,
    private val resetBinding: () -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val changed: (NotificationReadState) -> Unit,
    private val replace: (List<NavigationFeed.Entry>, NotificationReadState) -> Unit,
    private val log: (String) -> Unit,
) {
    var state = NotificationReadState()
        private set

    private var reader: NavigationNotificationReader? = null
    private var attempts = 0
    private var pending = false
    private val timeout = Runnable {
        pending = false
        retry()
    }
    private val retryTask = Runnable {
        pending = false
        requestBind()
    }

    fun owns(candidate: NavigationNotificationReader) =
        reader === candidate && state.phase == NotificationReadPhase.CONNECTED

    fun refresh(manual: Boolean = false, restart: Boolean = false) {
        if (!checkPermission()) return
        if (restart) {
            clearTimers()
            val previous = reader
            reader = null
            attempts = 0
            publish(NotificationReadPhase.DISCONNECTED, "알림 리스너 재연결 중")
            runCatching { if (previous != null) previous.unbind() else resetBinding() }
                .onFailure { log("알림 리스너 해제 실패: ${it.javaClass.simpleName}") }
            // requestUnbind disables callbacks immediately; do not wait for onListenerDisconnected.
            pending = true
            schedule(retryTask, 500)
        } else if (reader != null) {
            synchronize()
        } else if (!pending) {
            if (manual) attempts = 0
            if (attempts < 3) requestBind()
        }
    }

    fun connected(candidate: NavigationNotificationReader) {
        if (!checkPermission()) return
        clearTimers()
        reader = candidate
        synchronize()
    }

    fun disconnected(candidate: NavigationNotificationReader) {
        if (reader !== candidate) return
        reader = null
        clearTimers()
        publish(NotificationReadPhase.DISCONNECTED, "알림 리스너 연결 끊김, 복구 준비 중")
        if (checkPermission()) retry()
    }

    private fun synchronize() {
        val active = reader ?: return
        try {
            val entries = active.snapshot()
            if (reader !== active) return
            clearTimers()
            attempts = 0
            // Publish the snapshot and readiness together, without briefly rendering stale
            // directions.
            state = NotificationReadState(NotificationReadPhase.CONNECTED, "알림 리스너 연결됨")
            replace(entries, state)
            log("알림 스냅샷 동기화: 길 안내 후보 ${entries.size}개, 파싱 가능 ${entries.count { it.instruction != null }}개")
        } catch (failure: Exception) {
            if (reader !== active) return
            reader = null
            publish(NotificationReadPhase.DISCONNECTED, "알림 읽기 실패, 리스너 복구 중")
            log("알림 스냅샷 읽기 실패: ${failure.javaClass.simpleName}")
            runCatching { active.unbind() }
            retry()
        }
    }

    private fun requestBind() {
        if (!checkPermission() || reader != null) return
        if (attempts >= 3) {
            failed()
            return
        }
        attempts++
        pending = true
        publish(NotificationReadPhase.CONNECTING, "권한 허용됨, 알림 리스너 연결 중 ($attempts/3)")
        schedule(timeout, 8_000)
        try {
            bind()
        } catch (failure: Exception) {
            cancel(timeout)
            pending = false
            log("알림 리스너 재연결 요청 실패: ${failure.javaClass.simpleName}")
            retry()
        }
    }

    private fun retry() {
        if (!checkPermission()) return
        if (attempts >= 3) {
            failed()
            return
        }
        // A failed system bind can leave the component enabled. Rebind alone then does nothing.
        // Rebuild that binding before the next delayed attempt; never toggle notification access.
        if (attempts > 0)
            runCatching { resetBinding() }
                .onFailure { log("알림 리스너 바인딩 재설정 실패: ${it.javaClass.simpleName}") }
        pending = true
        publish(NotificationReadPhase.CONNECTING, "권한 허용됨, 알림 리스너 복구 대기")
        schedule(retryTask, if (attempts <= 1) 2_000 else 5_000)
    }

    private fun failed() {
        clearTimers()
        publish(NotificationReadPhase.FAILED, "알림 권한은 허용되었지만 리스너가 연결되지 않았습니다. '알림 읽기 복구'를 누르세요")
    }

    private fun checkPermission(): Boolean {
        if (authorized()) return true
        clearTimers()
        reader = null
        attempts = 0
        publish(NotificationReadPhase.NOT_GRANTED, "알림 접근 권한이 아직 꺼져 있음")
        return false
    }

    private fun clearTimers() {
        cancel(timeout)
        cancel(retryTask)
        pending = false
    }

    private fun publish(phase: NotificationReadPhase, detail: String) {
        val next = NotificationReadState(phase, detail)
        if (state == next) return
        state = next
        changed(next)
        log(detail)
    }
}
