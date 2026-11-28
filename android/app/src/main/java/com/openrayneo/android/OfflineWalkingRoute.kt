// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import btools.routingapp.IBRouterService
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Opt-in local route source, separate from NAVER. Public BRouter AIDL and
 * FormatJson/Formatter contracts at abrensch/brouter ff7b616d. No network,
 * background location or automatic provider switch in OpenRayneo. */
internal object OfflineWalkingRoute {
    private val component = ComponentName("btools.routingapp", "btools.routingapp.BRouterService")
    /** Only checks the public service is installed and accessible; local map
     * coverage and actual route calculation remain per-request checks. */
    @Suppress("DEPRECATION")
    fun serviceAvailable(context: Context): Boolean = runCatching {
        val info = context.packageManager.getServiceInfo(component, 0)
        info.enabled && info.exported && info.applicationInfo.enabled &&
            (info.permission.isNullOrEmpty() || context.checkSelfPermission(info.permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)
    data class Turn(val pointIndex: Int, val sourceCode: Int, val exit: Int,
                    val nextDistanceMeters: Double, val angleDegrees: Int)
    class Route(val course: NaverWalkingCourse.Course, val turns: List<Turn>,
                val pointTimesSeconds: List<Double>?, val estimatedSeconds: Double?) {
        override fun toString() = "OfflineWalkingRoute(points=${course.points.size})"
    }

    fun coordinate(text: String): NaverWalkingCourse.Point {
        val parts = text.split(',')
        require(parts.size == 2) { "위도, 경도 순서로 입력하세요." }
        val lat = parts[0].trim().toDoubleOrNull()
        val lon = parts[1].trim().toDoubleOrNull()
        require(lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0) { "유효한 위도, 경도를 입력하세요." }
        return NaverWalkingCourse.Point(lat, lon)
    }

    private fun meters(a: NaverWalkingCourse.Point, b: NaverWalkingCourse.Point): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        return 12_742_000.0 * asin(sqrt((sin(dLat/2).pow(2) +
            cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(dLon/2).pow(2)).coerceIn(0.0,1.0)))
    }

    /** Call only on a worker after an explicit user action. AIDL call itself
     * cannot be cancelled remotely; provider is requested to cap calculation
     * at 30 seconds. No returned error string/coordinates are written to logs. */
    fun calculate(context: Context, start: NaverWalkingCourse.Point,
                  end: NaverWalkingCourse.Point): Route {
        require(meters(start,end) in 10.0..50_000.0) { "출발점과 도착점은 10m~50km 범위로 선택하세요." }
        val ready = CountDownLatch(1)
        val remote = java.util.concurrent.atomic.AtomicReference<IBRouterService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (name == component) remote.set(IBRouterService.Stub.asInterface(binder))
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null); ready.countDown() }
            override fun onBindingDied(name: ComponentName) { remote.set(null); ready.countDown() }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
        }
        val bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        check(bound) { "BRouter 설치 및 오프라인 데이터 준비가 필요합니다." }
        try {
            check(ready.await(5, TimeUnit.SECONDS)) { "BRouter 연결 시간 초과" }
            val service = checkNotNull(remote.get()) { "BRouter 연결 실패" }
            val input = Bundle().apply {
                putDoubleArray("lats", doubleArrayOf(start.latitude, end.latitude))
                putDoubleArray("lons", doubleArrayOf(start.longitude, end.longitude))
                putString("v", "foot"); putString("fast", "1")
                putString("trackFormat", "json"); putString("timode", "2")
                putString("maxRunningTime", "30")
            }
            val raw = service.getTrackFromParams(input)
            check(raw != null && raw.length in 1..1_048_576 && raw.trimStart().startsWith("{")) {
                "BRouter 경로 계산 실패 · 지역 데이터 및 도보 프로필을 확인하세요."
            }
            return parse(raw, start, end)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun integer(v: Any?): Int? = number(v)?.takeIf { it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && it == floor(it) }?.toInt()

    /** Consume exact provider route geometry and indexed voice hints; provider
     * code is NOT a RayNeo maneuver enum. No fallback to guessed turns or ETA. */
    fun parse(raw: String, start: NaverWalkingCourse.Point, end: NaverWalkingCourse.Point): Route {
        require(raw.length in 1..1_048_576)
        val root = JSONObject(raw)
        require(root.optString("type") == "FeatureCollection")
        val features = root.getJSONArray("features")
        require(features.length() == 1)
        val feature = features.getJSONObject(0)
        require(feature.optString("type") == "Feature")
        val props = feature.getJSONObject("properties")
        require(props.optString("creator").startsWith("BRouter-"))
        val geometry = feature.getJSONObject("geometry")
        require(geometry.optString("type") == "LineString")
        val points = geometry.getJSONArray("coordinates")
        require(points.length() in 2..30_000)
        val coords = (0 until points.length()).map { i ->
            val row = points.getJSONArray(i)
            require(row.length() in 2..3)
            val lon = requireNotNull(number(row.opt(0)))
            val lat = requireNotNull(number(row.opt(1)))
            require(lat in -90.0..90.0 && lon in -180.0..180.0)
            NaverWalkingCourse.Point(lat, lon)
        }
        require(meters(start,coords.first()) <= 100.0 && meters(end,coords.last()) <= 100.0) {
            "계산된 경로가 요청한 출발점 또는 도착점에서 너무 멉니다."
        }
        val course = NaverWalkingCourse.Course(coords)
        require(course.lengthMeters.isFinite() && course.lengthMeters in 1.0..200_000.0)
        val hints = props.optJSONArray("voicehints")
        val turns = if (hints == null) emptyList() else {
            require(hints.length() <= coords.size * 2)
            (0 until hints.length()).map { i ->
                val row = hints.getJSONArray(i)
                require(row.length() == 5)
                val index = requireNotNull(integer(row.opt(0)))
                val code = requireNotNull(integer(row.opt(1)))
                val exit = requireNotNull(integer(row.opt(2)))
                val distance = requireNotNull(number(row.opt(3)))
                val angle = requireNotNull(integer(row.opt(4)))
                // Explicit off-route or direct-line segments are not accepted
                // as a walkable calculated route.
                require(index in coords.indices && code in 1..18 && code !in setOf(12,16) &&
                    exit in -32..32 && distance in 0.0..200_000.0 && angle in -360..360)
                Turn(index,code,exit,distance,angle)
            }.also { require(it.zipWithNext().all { (x,y) -> x.pointIndex <= y.pointIndex }) }
        }
        val total = props.optString("total-time").toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..604800.0 }
        val times = props.optJSONArray("times")?.let { seq ->
            require(seq.length() == coords.size)
            (0 until seq.length()).map { i -> requireNotNull(number(seq.opt(i))).also { require(it in 0.0..604800.0) } }
                .also { require(it.zipWithNext().all { (x,y) -> x <= y }) }
        }
        return Route(course,turns,times,total)
    }
}
