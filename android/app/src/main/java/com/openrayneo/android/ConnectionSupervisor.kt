// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler

/** A previously authenticated pair waits silently for a connectable advertisement.
 * No periodic connection attempts while the glasses are absent, no retry-count cutoff.
 * Explicit disconnect, scan or service stop cancels the in-memory recovery permission.
 */
@SuppressLint("MissingPermission")
internal class ConnectionSupervisor(
    private val context: Context,
    private val main: Handler,
    private val changed: (LinkState) -> Unit,
    private val discovered: (RayneoConnection.Device) -> Unit,
    private val received: (ProtocolCodec.Frame, String) -> Unit,
    private val report: (String) -> Unit,
) : RayneoConnection.Listener {
    val transport = RayneoConnection(context, this)
    var state = LinkState(); private set
    private var target: RayneoConnection.Device? = null
    private var waiting = false
    private var automatic = false
    private var bleOnly = false
    private var failures = 0
    private var closed = false
    private var watchScheduled = false
    private var watching = false
    private var connectedAt = 0L
    private val remembered = AppSettings(context, "last_authenticated_glasses")
    private var restoreAttempted = false
    private val history = ConnectionHealth(context)
    val health: String get() = history.summary()
    private val cooldowns = longArrayOf(1_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000)
    private val retry = Runnable { watchScheduled = false; startWatch() }
    private val adapterChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    main.removeCallbacks(retry); watchScheduled = false; watching = false; transport.stopPresenceWatch()
                    // Android may turn the adapter off without delivering the final GATT/socket callback.
                    // Invalidate both transports here; onState retains a previously authenticated target.
                    if (state.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY, LinkPhase.CONNECTING) || automatic) {
                        transport.abortConnection("휴대폰 블루투스 꺼짐")
                    } else if (waiting) publish(LinkState(LinkPhase.DISCONNECTED, "안경 미연결 · 블루투스 꺼짐", state.lastFailure, true))
                }
                BluetoothAdapter.STATE_ON -> if (waiting && target != null && !automatic) {
                    failures = 0; scheduleWatch(1_000)
                }
            }
        }
    }
    init {
        context.registerReceiver(adapterChanges, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }
    private fun publish(next: LinkState) {
        if (state != next) { state = next; changed(state) }
    }
    /** Restore only on an explicitly opened app, for the previously authenticated,
     * still-bonded device. Never restore a recording or create a new system bond. */
    fun restoreLast(): Boolean {
        if (closed || restoreAttempted || state.phase != LinkPhase.DISCONNECTED || target != null || !recoveryPermissions()) return false
        val saved = runCatching { remembered.read() }.getOrElse { report("이전 연결 설정 읽기 실패"); return false }
        val classic = saved.optString("classic")
        val ble = saved.optString("ble")
        if (!BluetoothAdapter.checkBluetoothAddress(classic) || !BluetoothAdapter.checkBluetoothAddress(ble)) {
            restoreAttempted = true; return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return false
        if (!adapter.isEnabled) return false
        restoreAttempted = true
        val device = runCatching {
            val paired = adapter.getRemoteDevice(classic)
            if (paired.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) null
            else RayneoConnection.Device(saved.optString("name", "RayNeo iO"), adapter.getRemoteDevice(ble), classic)
        }.getOrNull() ?: return false
        target = device; bleOnly = false; failures = 0
        scheduleWatch(1_000)
        return true
    }
    private fun rememberAuthenticated(device: RayneoConnection.Device) {
        val classic = device.classicAddress ?: return
        runCatching {
            remembered.save(org.json.JSONObject().put("name", device.name).put("classic", classic).put("ble", device.bluetooth.address))
        }.onFailure { report("자동 연결 설정 저장 실패") }
    }
    fun forgetRecovery() {
        restoreAttempted = true
        runCatching { remembered.save(org.json.JSONObject()) }.onFailure { report("자동 연결 설정 해제 실패") }
        cancelRetries()
    }
    fun scan() { forgetRecovery(); transport.scan() }
    fun connect(device: RayneoConnection.Device, diagnostic: Boolean) {
        forgetRecovery(); bleOnly = diagnostic
        if (!diagnostic) target = device
        try { transport.connect(device, diagnostic) }
        catch (failure: Exception) { transport.abortConnection(failure.message ?: "초기 연결 실패") }
    }
    fun cancelRetries() {
        target = null; waiting = false; automatic = false; failures = 0
        main.removeCallbacks(retry); watchScheduled = false; watching = false; transport.stopPresenceWatch()
    }
    fun disconnect() { cancelRetries(); transport.disconnect() }
    fun close() {
        closed = true; cancelRetries()
        runCatching { context.unregisterReceiver(adapterChanges) }
        transport.close()
        history.clearActive()
    }
    fun recoveryPermissions(): Boolean = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun adapterEnabled(): Boolean = recoveryPermissions() && runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
    private fun scheduleWatch(delay: Long, reason: String? = state.lastFailure) {
        if (closed || target?.classicAddress == null) return
        waiting = true; automatic = false
        val detail = if (adapterEnabled()) "안경 미연결 · 자동 재연결 대기" else "안경 미연결 · 블루투스 또는 근처 기기 권한 확인"
        publish(LinkState(LinkPhase.DISCONNECTED, detail, reason, reconnectWaiting = true))
        main.removeCallbacks(retry)
        watchScheduled = false; watching = false
        if (adapterEnabled()) { watchScheduled = true; main.postDelayed(retry, delay) }
    }
    private fun retryWatch(reason: String) {
        if (closed || target == null) return
        watching = false
        history.event("자동 복구 실패", reason)
        report(reason)
        failures = (failures + 1).coerceAtMost(cooldowns.lastIndex)
        scheduleWatch(cooldowns[failures], reason)
    }
    private fun startWatch() {
        val expected = target ?: return
        if (closed || !waiting || automatic || !adapterEnabled()) return
        watching = true
        runCatching {
            transport.watchPresence(expected, { device ->
                if (!closed && waiting && target === expected && !automatic) {
                    watching = false; waiting = false; automatic = true
                    target = device // Preserve the latest observed BLE address for future recovery.
                    runCatching { transport.connect(device, automatic = true) }
                        .onFailure { transport.abortConnection(it.message ?: "자동 연결 실패") }
                }
            }, ::retryWatch)
        }.onFailure { retryWatch(it.message ?: "자동 감지 실패") }
    }
    fun permissionsChanged() {
        if (closed || !waiting || automatic || target == null) return
        if (!adapterEnabled()) {
            main.removeCallbacks(retry); watchScheduled = false; watching = false; transport.stopPresenceWatch()
        } else if (!watchScheduled && !watching) scheduleWatch(1_000)
    }
    override fun onDevice(device: RayneoConnection.Device) = discovered(device)
    override fun onState(text: String, ready: Boolean, busy: Boolean) {
        val wasReady = state.phase == LinkPhase.FULL
        if (ready) {
            val recovered = automatic
            waiting = false; automatic = false; failures = 0; main.removeCallbacks(retry)
            watchScheduled = false; watching = false
            if (!bleOnly && !wasReady) {
                connectedAt = android.os.SystemClock.elapsedRealtime()
                history.event(if (recovered) "자동 재연결 성공" else "연결 성공", active = true)
            }
            target = target?.copy(classicAddress = transport.resolvedClassicAddress ?: target?.classicAddress)
            if (!bleOnly) target?.let(::rememberAuthenticated)
            publish(LinkState(if (bleOnly) LinkPhase.BLE_ONLY else LinkPhase.FULL, text))
            return
        }
        if (!busy && target?.classicAddress != null && (wasReady || automatic)) {
            if (wasReady) {
                history.event("연결 끊김", text, ((android.os.SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L) / 1000), active = false)
                failures = 0; scheduleWatch(1_000, text)
            }
            else retryWatch(text)
            return
        }
        if (wasReady && !busy) history.event("연결 종료", text, ((android.os.SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L) / 1000), active = false)
        if (waiting) return // Late callbacks cannot replace the retained failure.
        val phase = if (busy) {
            if (text.startsWith("스캔 중")) LinkPhase.SCANNING else LinkPhase.CONNECTING
        } else LinkPhase.DISCONNECTED
        val failure = when {
            busy -> state.lastFailure
            text == "연결 끊김" || text.startsWith("스캔 완료") -> null
            else -> text
        }
        publish(LinkState(phase, text, failure))
    }
    override fun onFrame(frame: ProtocolCodec.Frame, source: String) = received(frame, source)
    override fun onLog(text: String) = report(text)
}
