// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONObject

/** Explicitly selected alternative routing source; never reuse NAVER geocodes
 * or modify its stored destinations or credentials. */
internal object OfflineNativeRouteSettings {
    data class Config(val enabled: Boolean = false, val home: String = "", val company: String = "") {
        fun resolve(destination: String): NaverWalkingCourse.Point? {
            if (!enabled) return null
            val coordinates = when (destination) { "집" -> home; "회사" -> company; else -> destination }
            return runCatching { OfflineWalkingRoute.coordinate(coordinates) }.getOrNull()
        }
        override fun toString() = "OfflineNativeRouteSettings(enabled=$enabled)"
    }
    // Stock registry describes the implemented, configured phone capability,
    // not transient GPS availability. Do not withdraw an installed handler just
    // because location is temporarily disabled; reject actual START separately.
    fun supportsConfiguredProvider(selected: Boolean, serviceAvailable: Boolean, wireAvailable: Boolean): Boolean =
        selected && serviceAvailable && wireAvailable

    // Operational gate for route computation / live guidance, NOT registry.
    fun canAdvertise(selected: Boolean, serviceAvailable: Boolean, wireAvailable: Boolean,
                     preciseLocation: Boolean, locationService: Boolean, gpsEnabled: Boolean): Boolean =
        selected && serviceAvailable && wireAvailable && preciseLocation && locationService && gpsEnabled

    private fun settings(context: Context) = AppSettings(context, "offline_native_navigation")
    fun read(context: Context): Config {
        val value = settings(context).readStored()
        return Config(value.optBoolean("enabled"), value.optString("home"), value.optString("company"))
    }
    fun save(context: Context, config: Config) {
        for (value in listOf(config.home, config.company)) {
            if (value.isNotBlank()) OfflineWalkingRoute.coordinate(value)
        }
        settings(context).save(JSONObject().put("enabled", config.enabled)
            .put("home", config.home.trim()).put("company", config.company.trim()))
    }
}
