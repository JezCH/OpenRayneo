// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import kotlin.math.*

/** Provider-neutral progress against actual calculated geometry. An ambiguous
 * match (parallel paths, loops, crossing) is not a usable navigation fix.
 * Never reinterpret BRouter turn codes as stock RayNeo maneuver codes. */
internal object OfflineRouteProgress {
    data class Fix(val point: NaverWalkingCourse.Point, val accuracyMeters: Double, val observedAtMs: Long, val speedMpsX100: Int? = null)
    data class Progress(val observedAtMs: Long, val remainingMeters: Int,
                        val nextTurn: OfflineWalkingRoute.Turn?, val nextTurnMeters: Int?,
                        val remainingEstimateSeconds: Int?, val lateralMeters: Int,
                        val followingTurn: OfflineWalkingRoute.Turn? = null)
    private data class Candidate(val segment: Int, val fraction: Double, val lateral: Double, val along: Double)
    private const val R = 6_371_000.0
    private fun longitudeDelta(a: Double, b: Double): Double {
        val d = b-a
        return when { d > 180 -> d-360; d < -180 -> d+360; else -> d }
    }
    private fun distance(a: NaverWalkingCourse.Point,b: NaverWalkingCourse.Point): Double {
        val lat=Math.toRadians(b.latitude-a.latitude)
        val lon=Math.toRadians(longitudeDelta(a.longitude,b.longitude))
        return 2*R*asin(sqrt((sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)).coerceIn(0.0,1.0)))
    }
    data class Assessment(val progress: Progress?, val confidentlyOffRoute: Boolean = false)
    fun match(route: OfflineWalkingRoute.Route, fix: Fix, nowMs: Long): Progress? =
        assess(route, fix, nowMs).progress

    /** Absence of a match is not proof of deviation: ambiguous crossings,
     * poor GPS and incomplete geometry must never trigger a new route. */
    fun assess(route: OfflineWalkingRoute.Route, fix: Fix, nowMs: Long): Assessment {
        if (fix.observedAtMs < 0 || nowMs < fix.observedAtMs ||
            nowMs-fix.observedAtMs >= NativePhoneGuidance.VALID_FOR_MS ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0.0..25.0 ||
            !fix.point.latitude.isFinite() || !fix.point.longitude.isFinite() ||
            fix.point.latitude !in -90.0..90.0 || fix.point.longitude !in -180.0..180.0) return Assessment(null)
        val points=route.course.points
        if (points.size !in 2..30_000) return Assessment(null)
        val prefix=DoubleArray(points.size)
        val candidates=ArrayList<Candidate>()
        val scale=cos(Math.toRadians(fix.point.latitude))
        fun xy(p: NaverWalkingCourse.Point) = Pair(
            R*Math.toRadians(longitudeDelta(fix.point.longitude,p.longitude))*scale,
            R*Math.toRadians(p.latitude-fix.point.latitude))
        val maxLateral=minOf(30.0,fix.accuracyMeters+12.0)
        var minLateral=Double.POSITIVE_INFINITY
        var incomplete=false
        for(i in 0 until points.lastIndex) {
            val length=distance(points[i],points[i+1])
            prefix[i+1]=prefix[i]+length
            if(length > 1000.0) { incomplete=true; continue }
            if(length < 0.01) continue
            val (ax,ay)=xy(points[i]); val (bx,by)=xy(points[i+1])
            val dx=bx-ax; val dy=by-ay; val squared=dx*dx+dy*dy
            if(squared < 0.0001) continue
            val f=(-(ax*dx+ay*dy)/squared).coerceIn(0.0,1.0)
            val lateral=hypot(ax+f*dx,ay+f*dy)
            minLateral=minOf(minLateral,lateral)
            if(lateral<=maxLateral) candidates.add(Candidate(i,f,lateral,prefix[i]+f*length))
        }
        val best=candidates.minByOrNull { it.lateral } ?: return Assessment(null,
            !incomplete && minLateral.isFinite() && fix.accuracyMeters <= 15.0 &&
                minLateral > 45.0 + fix.accuracyMeters)
        // Adjacent projections of the same junction are equivalent; a
        // materially different along-route position within GPS error is not.
        if(candidates.any { abs(it.along-best.along)>maxOf(25.0,2*fix.accuracyMeters) &&
                it.lateral<=best.lateral+fix.accuracyMeters }) return Assessment(null)
        val remaining=(prefix.last()-best.along).coerceAtLeast(0.0)
        // If a fix is near a turn, retain it until the measured progress
        // exceeds its location by the uncertainty margin; never declare arrival.
        val nextIndex=route.turns.indexOfFirst {
            it.pointIndex in prefix.indices && prefix[it.pointIndex]>=best.along-fix.accuracyMeters
        }
        val next=route.turns.getOrNull(nextIndex)
        val following=if(next!=null) route.turns.drop(nextIndex+1).firstOrNull {
            it.pointIndex>next.pointIndex
        } else null
        val nextDistance=next?.let { (prefix[it.pointIndex]-best.along).coerceAtLeast(0.0).roundToInt() }
        val times=route.pointTimesSeconds
        val estimate=if(times!=null && times.size==points.size && times.last()>times.first()) {
            val elapsed=times[best.segment]+best.fraction*(times[best.segment+1]-times[best.segment])
            (times.last()-elapsed).takeIf { it.isFinite() && it in 0.0..604800.0 }?.roundToInt()
        } else null
        return Assessment(Progress(fix.observedAtMs,remaining.roundToInt(),next,nextDistance,estimate,best.lateral.roundToInt(),following))
    }
}
