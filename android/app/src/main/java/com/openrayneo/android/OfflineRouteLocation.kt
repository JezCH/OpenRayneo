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
        return OfflineRouteProgress.Fix(NaverWalkingCourse.Point(value.latitude,value.longitude),value.accuracy.toDouble(),stamp)
    }
    @SuppressLint("MissingPermission")
    fun current(context: Context): OfflineRouteProgress.Fix? {
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        val manager=context.getSystemService(LocationManager::class.java) ?: return null
        if(!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
        val cancel=CancellationSignal()
        val received=AtomicReference<Location?>()
        val latch=CountDownLatch(1)
        try {
            manager.getCurrentLocation(LocationManager.GPS_PROVIDER,cancel,context.mainExecutor) {
                received.set(it); latch.countDown()
            }
            if(!latch.await(12,TimeUnit.SECONDS)) return null
            val value=received.get() ?: return null
            return toFix(value)
        } finally { cancel.cancel() }
    }
}
