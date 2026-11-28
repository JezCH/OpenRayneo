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
        val turn=progress.nextTurn
        if(progress.observedAtMs!=fix.observedAtMs) return null
        // No remaining provider hint is not a straight/arrival instruction.
        // Retain real distances and map, using only a verified no-maneuver code.
        val primary=if(turn==null) wire.noManeuver else
            wire.maneuver(turn.maneuver ?: return null) ?: return null
        // An unsupported following turn must not suppress a verified current one.
        val following=progress.followingTurn?.maneuver?.let(wire::maneuver) ?: wire.noManeuver
        val instruction=if(turn==null) "" else turn.instruction ?: return null
        val exit=if(turn!=null && turn.sourceCode in 13..14) kotlin.math.abs(turn.exit) else 0
        val nextDistance=if(turn==null) -1 else progress.nextTurnMeters ?: return null
        // A mapping that contradicts verified firmware constraints is not usable.
        val guidance=runCatching {
            NativePhoneGuidance.Snapshot(progress.observedAtMs,wire.enrouteState,
                primary,following,exit,fix.speedMpsX100 ?: -1,
                nextDistance,progress.remainingMeters,
                progress.remainingEstimateSeconds ?: -1,"","",instruction,false,false)
        }.getOrNull() ?: return null
        return NativeLiveNavigation.Observation(guidance,sample.miniPng,sample.overviewPng)
    }
    override fun close() { closed=true }
}
