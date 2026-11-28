// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors

/** Own the real, locally calculated course and measured GPS for ONE native
 * session. The route token identifies our own immutable route, not NAVER.
 * The wire contract must be independently verified before enabling sending. */
internal class OfflineNativeRoute(
    context: Context,
    private val course: OfflineWalkingRoute.Route,
    override val parameters: NativeNavigationStartup.Parameters,
    private val wire: Wire,
    internal val requiresProviderSelection: Boolean = false,
    private val changed: () -> Unit
) : NativeLiveNavigation.Route {
    internal class Wire(val travelMode: Int, val guidingState: Int, val unitSystem: Int,
                        val drivingSide: Int, val noManeuver: Int, private val maneuvers: Map<Int, Int>) {
        init {
            require(travelMode in 1..3 && guidingState in 1..3 &&
                unitSystem in 1..2 && drivingSide in 0..2)
            require(noManeuver in 0..24 && maneuvers.values.all { it in 0..24 && it != noManeuver })
        }
        fun maneuver(code: Int?): Int? = if(code==null) noManeuver else maneuvers[code]
    }
    companion object {
        // Official app 1.0.30, libapp SHA 5d196b14..., exact
        // 0x1c4f8f4 -> PP+0x52650 (60-entry semantic map).
        // Evidence: Rayneo-Original runs 46144064096 / 46144213745.
        // BRouter Formatter timode=2 source codes -> canonical RayNeo wire.
        // Generic U-turn (15) has no verified direction; exit 17/18 is
        // not proven equivalent to an on/off-ramp. Do not guess them.
        internal val verifiedManeuvers: Map<Int, Int> = mapOf(
            1 to 2, 2 to 4, 3 to 3, 4 to 5,
            5 to 7, 6 to 6, 7 to 8, 8 to 17, 9 to 18,
            10 to 9, 11 to 10, 13 to 19, 14 to 19
        )
        internal const val VERIFIED_NO_MANEUVER = 0
        internal const val VERIFIED_GUIDING_STATE = 1
        // Exact stock OverseaSessionProfile construction: AOT
        // 0x33edb6c..0x33edb80 selects ride=3, walk=2;
        // 0x33edc10..0x33edcc8 selects US=2, other regions=1;
        // 0x33edc50..0x33edcd8 selects left-traffic region set=2,
        // others=1. KR is neither US nor the left-traffic region set.
        // Evidence: Rayneo-Original runs 46144382648 and 46144765172.
        internal const val VERIFIED_WALKING_MODE = 2
        internal const val VERIFIED_KR_UNIT_SYSTEM = 1
        internal const val VERIFIED_KR_DRIVING_SIDE = 1
        fun verifiedWire(): Wire? = Wire(VERIFIED_WALKING_MODE, VERIFIED_GUIDING_STATE,
            VERIFIED_KR_UNIT_SYSTEM, VERIFIED_KR_DRIVING_SIDE,
            VERIFIED_NO_MANEUVER, verifiedManeuvers)

        // Exact BRouter VoiceHint.java at ff7b616df988dbd82c9869368638065a7595b11d.
        // These are text labels, not RayNeo maneuver integers.
        fun turnLabel(code: Int?): String = when (code) {
            1 -> "직진"; 2 -> "좌회전"; 3 -> "왼쪽 전방"; 4 -> "급좌회전"
            5 -> "우회전"; 6 -> "오른쪽 전방"; 7 -> "급우회전"
            8 -> "왼쪽 유지"; 9 -> "오른쪽 유지"; 10 -> "좌측 유턴"
            11 -> "우측 유턴"; 13, 14 -> "회전교차로"; 15 -> "유턴"
            17 -> "왼쪽 출구"; 18 -> "오른쪽 출구"
            else -> ""
        }
        fun snapshot(progress: OfflineRouteProgress.Progress, wire: Wire,
                     speedMpsX100: Int? = null): NativePhoneGuidance.Snapshot? {
            val turn = progress.nextTurn
            // Unknown primary is not equivalent to "no maneuver". Do not
            // send an unsupported direction or silently use a legacy code.
            val primary = turn?.nativeManeuver ?: wire.maneuver(turn?.sourceCode) ?: return null
            val following = progress.followingTurn?.nativeManeuver ?: wire.maneuver(progress.followingTurn?.sourceCode) ?: wire.noManeuver
            val roundabout = turn?.sourceCode in 13..14
            val exit = if(roundabout) {
                val hint=checkNotNull(turn)
                if((hint.sourceCode==13 && hint.exit !in 1..32) ||
                    (hint.sourceCode==14 && hint.exit !in -32..-1)) return null
                kotlin.math.abs(hint.exit)
            } else {
                if(turn!=null && turn.exit!=0) return null
                0
            }
            val instruction=turn?.instruction ?: if(roundabout) "회전교차로 ${exit}번 출구" else turnLabel(turn?.sourceCode)
            val distance=if(turn==null) -1 else progress.nextTurnMeters ?: return null
            return runCatching {
                NativePhoneGuidance.Snapshot(progress.observedAtMs, wire.guidingState,
                    primary, following, exit, speedMpsX100 ?: -1, distance, progress.remainingMeters,
                    progress.remainingEstimateSeconds ?: -1, "", "", instruction, false, false)
            }.getOrNull()
        }
    }
    internal val provider get() = course.provider
    override val generation = 1L
    override val token = UUID.randomUUID().toString()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var watch: AutoCloseable? = null
    private var closed = false
    private var busy = false
    private var latest: OfflineRouteProgress.Fix? = null
    private var current: NativeLiveNavigation.Observation? = null
    private var revision = 0L
    private var lastSeenAt = -1L
    init {
        try {
            watch = OfflineRouteLocation.watch(context) { fix ->
                if (!closed) {
                    if (fix == null || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0.0..25.0) {
                        ++revision
                        latest = null
                        current = null
                        changed()
                    } else if (fix.observedAtMs > lastSeenAt) {
                        lastSeenAt = fix.observedAtMs
                        latest = fix
                        process()
                    }
                }
            }
        } catch (error: Exception) {
            worker.shutdownNow()
            throw error
        }
    }
    private fun process() {
        if (closed || busy) return
        val fix = latest ?: return
        val ticket = revision
        latest = null
        busy = true
        worker.execute {
            val result = runCatching {
                val progress = OfflineRouteProgress.match(course, fix, SystemClock.elapsedRealtime())
                    ?: return@runCatching null
                val guidance = snapshot(progress, wire, fix.speedMpsX100) ?: return@runCatching null
                val mini = NativeCourseRaster.render(course.course, NativeRasterFrame.View.MINI, fix.point)
                val full = NativeCourseRaster.render(course.course, NativeRasterFrame.View.OVERVIEW, fix.point)
                NativeLiveNavigation.Observation(guidance, mini, full)
            }.getOrNull()
            main.post {
                busy = false
                if (closed) return@post
                if (ticket == revision) {
                    current = result?.takeIf { it.guidance.freshAt(SystemClock.elapsedRealtime()) }
                    changed()
                }
                process()
            }
        }
    }
    override fun observation(): NativeLiveNavigation.Observation? =
        current?.takeIf { !closed && it.guidance.freshAt(SystemClock.elapsedRealtime()) }
    override fun close() {
        if (closed) return
        closed = true
        ++revision
        latest = null
        current = null
        runCatching { watch?.close() }
        watch = null
        worker.shutdownNow()
    }
}
