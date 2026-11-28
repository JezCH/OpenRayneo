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
        // Only a reviewed, evidence-backed stock walking mapping may enable
        // this source. Ranges alone are not semantic enum evidence.
        fun verifiedWire(): Wire? = null

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
            val primary = wire.maneuver(turn?.sourceCode) ?: return null
            val following = wire.maneuver(progress.followingTurn?.sourceCode) ?: wire.noManeuver
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
            val instruction=if(roundabout) "회전교차로 ${exit}번 출구" else turnLabel(turn?.sourceCode)
            val distance=if(turn==null) -1 else progress.nextTurnMeters ?: return null
            return runCatching {
                NativePhoneGuidance.Snapshot(progress.observedAtMs, wire.guidingState,
                    primary, following, exit, speedMpsX100 ?: -1, distance, progress.remainingMeters,
                    progress.remainingEstimateSeconds ?: -1, "", "", instruction, false, false)
            }.getOrNull()
        }
    }
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
