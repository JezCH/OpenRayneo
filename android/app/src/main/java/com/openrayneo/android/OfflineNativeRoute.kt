// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Real route-source adapter for the existing stock session owner. No device
 * writes here; a firmware-evidence-backed code mapping must be supplied by the
 * caller. Until then no live route is registered or started. All methods run on
 * the same main loop as BridgeService. A reroute is a different route identity. */
internal class OfflineNativeRoute(
    private val route: OfflineWalkingRoute.Route,
    override val parameters: NativeNavigationStartup.Parameters,
    private val wire: WireMapping,
    private val current: () -> Sample?
) : NativeLiveNavigation.Route {
    interface WireMapping {
        val walkingMode: Int
        val enrouteState: Int
        val noManeuver: Int
        fun maneuver(value: OfflineWalkingRoute.Maneuver): Int?
    }
    class Sample(
        val route: OfflineWalkingRoute.Route,
        val progress: OfflineRouteProgress.Progress,
        val fix: OfflineRouteProgress.Fix,
        val miniPng: ByteArray,
        val overviewPng: ByteArray
    )
    override val generation = 1L
    override val token = java.util.UUID.randomUUID().toString()
    private var closed = false
    init {
        require(parameters.profile == NativeNavigationStartup.Profile.RASTER_FRAME)
        require(parameters.travelMode == wire.walkingMode && wire.enrouteState in 0..3 && wire.noManeuver in 0..24)
    }
    override fun observation(): NativeLiveNavigation.Observation? {
        if(closed) return null
        val sample=current()?.takeIf { it.route===route } ?: return null
        val progress=sample.progress
        val fix=sample.fix
        val turn=progress.nextTurn ?: return null // no guessed straight/destination/arrival
        if(progress.observedAtMs!=fix.observedAtMs) return null
        val primary=wire.maneuver(turn.maneuver ?: return null) ?: return null
        val following=progress.followingTurn?.let {
            wire.maneuver(it.maneuver ?: return null) ?: return null
        } ?: wire.noManeuver
        val instruction=turn.instruction ?: return null
        val exit=if(turn.sourceCode in 13..14) kotlin.math.abs(turn.exit) else 0
        val guidance=NativePhoneGuidance.Snapshot(progress.observedAtMs,wire.enrouteState,
            primary,following,exit,fix.speedMpsX100 ?: -1,
            progress.nextTurnMeters ?: return null,progress.remainingMeters,
            progress.remainingEstimateSeconds ?: -1,"","",instruction,false,false)
        return NativeLiveNavigation.Observation(guidance,sample.miniPng,sample.overviewPng)
    }
    override fun close() { closed=true }
}
