// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One explicit foreground request, existing precise permission only. Never
 * use cached five-minute weather fixes or request background access. */
internal object OfflineRouteLocation {
    @SuppressLint("MissingPermission")
    fun watch(context: Context, receive: (OfflineRouteProgress.Fix?) -> Unit): AutoCloseable {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
            "정확한 위치 권한이 필요합니다."
        }
        val manager=checkNotNull(context.getSystemService(LocationManager::class.java))
        check(manager.isLocationEnabled && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            "GPS를 활성화하세요."
        }
        val listener=object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) { receive(toFix(location)) }
            override fun onProviderDisabled(provider: String) { receive(null) }
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,listener,android.os.Looper.getMainLooper())
        return AutoCloseable { manager.removeUpdates(listener) }
    }
    private fun toFix(value: Location): OfflineRouteProgress.Fix? {
        if(value.isMock || !value.hasAccuracy()) return null
        val stamp=value.elapsedRealtimeNanos/1_000_000L
        val now=SystemClock.elapsedRealtime()
        if(stamp<0 || now<stamp || now-stamp>=NativePhoneGuidance.VALID_FOR_MS) return null
        val speed=if(value.hasSpeed() && value.speed.isFinite() && value.speed in 0f..200f)
            kotlin.math.round(value.speed*100f).toInt() else null
        return OfflineRouteProgress.Fix(NaverWalkingCourse.Point(value.latitude,value.longitude),value.accuracy.toDouble(),stamp,speed)
    }
    @SuppressLint("MissingPermission")
    fun current(context: Context): OfflineRouteProgress.Fix? {
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        val manager=context.getSystemService(LocationManager::class.java) ?: return null
        if(!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
        val received=AtomicReference<OfflineRouteProgress.Fix?>()
        val latch=CountDownLatch(1)
        // The first GNSS result may still be coarse while the receiver acquires
        // satellites. Wait for a measured, fresh usable fix within the existing
        // 12s budget, using only the same explicit foreground location permission.
        val listener=object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix=toFix(location) ?: return
                if (fix.accuracyMeters in 0.0..25.0 && received.compareAndSet(null,fix))
                    latch.countDown()
            }
            override fun onProviderDisabled(provider: String) { latch.countDown() }
        }
        var registered=false
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,listener,android.os.Looper.getMainLooper())
            registered=true
            if(!latch.await(12,TimeUnit.SECONDS)) return null
            val fix=received.get() ?: return null
            val now=SystemClock.elapsedRealtime()
            return fix.takeIf { now>=it.observedAtMs && now-it.observedAtMs<NativePhoneGuidance.VALID_FOR_MS }
        } finally { if(registered) manager.removeUpdates(listener) }
    }
}
