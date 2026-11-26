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
    private val cooldowns = longArrayOf(1_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000)
    private val retry = Runnable { startWatch() }
    private val adapterChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    main.removeCallbacks(retry); transport.stopPresenceWatch()
                    if (waiting) publish(LinkState(LinkPhase.DISCONNECTED, "안경 미연결 · 블루투스 꺼짐", state.lastFailure, true))
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
    fun scan() { cancelRetries(); transport.scan() }
    fun connect(device: RayneoConnection.Device, diagnostic: Boolean) {
        cancelRetries(); bleOnly = diagnostic
        if (!diagnostic) target = device
        try { transport.connect(device, diagnostic) }
        catch (failure: Exception) { transport.abortConnection(failure.message ?: "초기 연결 실패") }
    }
    fun cancelRetries() {
        target = null; waiting = false; automatic = false; failures = 0
        main.removeCallbacks(retry); transport.stopPresenceWatch()
    }
    fun disconnect() { cancelRetries(); transport.disconnect() }
    fun close() {
        closed = true; cancelRetries()
        runCatching { context.unregisterReceiver(adapterChanges) }
        transport.close()
    }
    fun recoveryPermissions(): Boolean = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun adapterEnabled(): Boolean = recoveryPermissions() && context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
    private fun scheduleWatch(delay: Long, reason: String? = state.lastFailure) {
        if (closed || target?.classicAddress == null) return
        waiting = true; automatic = false
        val detail = if (adapterEnabled()) "안경 미연결 · 자동 재연결 대기" else "안경 미연결 · 블루투스 또는 근처 기기 권한 확인"
        publish(LinkState(LinkPhase.DISCONNECTED, detail, reason, reconnectWaiting = true))
        main.removeCallbacks(retry)
        if (adapterEnabled()) main.postDelayed(retry, delay)
    }
    private fun retryWatch(reason: String) {
        if (closed || target == null) return
        report(reason)
        failures = (failures + 1).coerceAtMost(cooldowns.lastIndex)
        scheduleWatch(cooldowns[failures], reason)
    }
    private fun startWatch() {
        val expected = target ?: return
        if (closed || !waiting || automatic || !adapterEnabled()) return
        runCatching {
            transport.watchPresence(expected, { device ->
                if (!closed && waiting && target === expected && !automatic) {
                    waiting = false; automatic = true
                    runCatching { transport.connect(device, automatic = true) }
                        .onFailure { transport.abortConnection(it.message ?: "자동 연결 실패") }
                }
            }, ::retryWatch)
        }.onFailure { retryWatch(it.message ?: "자동 감지 실패") }
    }
    override fun onDevice(device: RayneoConnection.Device) = discovered(device)
    override fun onState(text: String, ready: Boolean, busy: Boolean) {
        val wasReady = state.phase == LinkPhase.FULL
        if (ready) {
            waiting = false; automatic = false; failures = 0; main.removeCallbacks(retry)
            target = target?.copy(classicAddress = transport.resolvedClassicAddress ?: target?.classicAddress)
            publish(LinkState(if (bleOnly) LinkPhase.BLE_ONLY else LinkPhase.FULL, text))
            return
        }
        if (!busy && target?.classicAddress != null && (wasReady || automatic)) {
            if (wasReady) { failures = 0; scheduleWatch(1_000, text) }
            else retryWatch(text)
            return
        }
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
