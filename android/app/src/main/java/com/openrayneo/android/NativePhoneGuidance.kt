// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/**
 * Phone-sourced navigation update 0x34. Stock 1.0.6.11 parser 0x107602C4,
 * envelope 0x105C4038 and first route registration 0x1073FF90.
 * Direct disassembly: Rayneo-Original runs 46127202707 / 46127359347.
 * The producer must provide a genuine, timestamped guidance snapshot. A
 * notification containing only a turn and distance is not a complete feed.
 */
internal object NativePhoneGuidance {
    const val VALID_FOR_MS = 3000L
    internal class Snapshot(
        val observedAtMs: Long, // same monotonic clock as nowMs
        val navState: Int,
        val maneuver: Int,
        val nextManeuver: Int,
        val roundaboutExit: Int,
        val speedMpsX100: Int,
        val nextActionDistanceM: Int,
        val routeRemainDistanceM: Int,
        val etaSeconds: Int,
        val currentRoadName: String,
        val nextRoadName: String,
        val instruction: String,
        val gpsSignalWeak: Boolean,
        val arrival: Boolean
    ) {
        init {
            require(observedAtMs >= 0 && navState in 0..4)
            require(maneuver in 0..24 && nextManeuver in 0..24)
            require(roundaboutExit in 0..32 && (roundaboutExit == 0 || maneuver in 19..20))
            require(speedMpsX100 in -1..20000 &&
                nextActionDistanceM in -1..2000000 && routeRemainDistanceM in -1..2000000 &&
                etaSeconds in -1..604800)
            require(arrival == (navState == 4))
            require(listOf(currentRoadName, nextRoadName).all {
                it.toByteArray(Charsets.UTF_8).size <= 192 && it.none(Char::isISOControl)
            } && instruction.toByteArray(Charsets.UTF_8).size <= 256 && instruction.none(Char::isISOControl))
        }
        fun freshAt(nowMs: Long) = nowMs >= observedAtMs && nowMs - observedAtMs < VALID_FOR_MS
        override fun toString() = "GuidanceSnapshot(redacted)"
    }

    fun frame(transportSequence: Int, route: NativeRouteScene.Identity, guidanceSequence: Long,
              parameters: NativeNavigationStartup.Parameters, value: Snapshot, nowMs: Long): ByteArray {
        require(transportSequence in 0..65535 && guidanceSequence in 1..0xffffffffL && value.freshAt(nowMs))
        // No guessed default maneuver, ETA or arrival state. Values are provider-supplied.
        val body = route.json().put("engine_profile", "PHONE_GUIDANCE_V1")
            .put("guidance_seq", guidanceSequence).put("nav_state", value.navState)
            .put("travel_mode", parameters.travelMode).put("maneuver", value.maneuver)
            .put("next_maneuver", value.nextManeuver).put("roundabout_exit", value.roundaboutExit)
            .put("driving_side", parameters.drivingSide).put("unit_system", parameters.unitSystem)
            .put("locale", parameters.locale).put("speed_mps_x100", value.speedMpsX100)
            .put("next_action_distance_m", value.nextActionDistanceM)
            .put("route_remain_distance_m", value.routeRemainDistanceM).put("eta_seconds", value.etaSeconds)
            .put("current_road_name", value.currentRoadName).put("next_road_name", value.nextRoadName)
            .put("instruction", value.instruction).put("gps_signal_weak", value.gpsSignalWeak)
            .put("arrival", value.arrival).put("valid_for_ms", VALID_FOR_MS)
        // 0x34 has neither binary nor nonzero inner sequence/mode.
        return ProtocolCodec.display(0x20, transportSequence, 0x34, body.toString())
    }
}
