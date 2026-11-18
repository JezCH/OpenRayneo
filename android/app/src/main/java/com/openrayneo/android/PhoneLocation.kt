// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One location fix per explicit tool call; no continuous tracking or Google Play dependency. */
internal class PhoneLocation(private val context: Context) {
    companion object {
        fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    @SuppressLint("MissingPermission")
    fun current(): JSONObject {
        check(allowed(context)) { "OpenRayneo 음성 설정에서 위치 접근을 허용한 뒤 현지 날씨를 조회하세요. 도시를 직접 알려 주셔도 됩니다" }
        val manager = context.getSystemService(LocationManager::class.java)
        check(manager.isLocationEnabled) { "휴대폰 위치가 꺼져 있습니다. 시스템 위치를 켜거나 도시를 알려 주세요" }
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..TimeUnit.MINUTES.toNanos(5) }
            .maxByOrNull { it.elapsedRealtimeNanos }
        var result: Location? = cached
        if (result == null) {
            check(providers.isNotEmpty()) { "휴대폰에 사용할 수 있는 위치 소스가 없습니다. 위치를 켜거나 도시를 알려 주세요" }
            val done = CountDownLatch(1)
            val signals = providers.map { CancellationSignal() }
            try {
                providers.forEachIndexed { index, provider ->
                    manager.getCurrentLocation(provider, signals[index], context.mainExecutor) { location ->
                        if (location != null && done.count > 0) { result = location; done.countDown() }
                    }
                }
                done.await(10, TimeUnit.SECONDS)
            } finally { signals.forEach { it.cancel() } }
        }
        val location = checkNotNull(result) { "이번에는 휴대폰 위치를 얻지 못했습니다. 실외에서 다시 시도하거나 도시를 알려 주세요" }
        return JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy).put("locatedAt", location.time)
            .put("source", "phone_location").put("name", "현재 위치")
    }
}
