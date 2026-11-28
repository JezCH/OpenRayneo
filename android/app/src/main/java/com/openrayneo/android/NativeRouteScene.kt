// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** Stock 1.0.6.11 scene body and 0x3C fragment encoding; no session creation or
 * transport side effects. Evidence runs 46124694432, 46124733543, 46124781551.
 * Callers must obtain real negotiated identity, travel mode and local geometry.
 * A prepared scene is NOT a live navigation session or permission to send it. */
internal object NativeRouteScene {
    private const val MAX_POINTS = 4096
    private const val MAX_BODY = 131072
    private const val FRAGMENT_SIZE = 4096
    internal data class Bounds(val north: Int, val south: Int, val east: Int, val west: Int) {
        fun json() = JSONObject().put("north_e6", north).put("south_e6", south)
            .put("east_e6", east).put("west_e6", west)
        override fun toString() = "Bounds(redacted)"
    }
    internal class Geometry internal constructor(val polyline6: String, val bounds: Bounds, val count: Int) {
        override fun toString() = "Geometry(count=$count)"
    }
    /** Preserve all points. Do not truncate, interpolate, or guess a local segment. */
    fun geometry(points: List<NaverWalkingCourse.Point>): Geometry? {
        if (points.size !in 2..MAX_POINTS || points.any {
                !it.latitude.isFinite() || !it.longitude.isFinite() ||
                    it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0
            }) return null
        val lat = points.map { (it.latitude * 1_000_000).roundToInt() }
        val lon = points.map { (it.longitude * 1_000_000).roundToInt() }
        if (lat.indices.none { lat[it] != lat[0] || lon[it] != lon[0] }) return null
        val bounds = Bounds(lat.max(), lat.min(), lon.max(), lon.min())
        // Conservative: no antimeridian-spanning box in this implementation.
        if (bounds.east.toLong() - bounds.west > 180_000_000L) return null
        return Geometry(NaverWalkingCourse.encodePolyline6(points), bounds, points.size)
    }

    internal class Identity(val sid: String, val revision: Long, val generation: Long, val token: String) {
        init {
            require(sid.matches(Regex("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")))
            require(revision in 1..0xffffffffL && generation in 1..0xffffffffL)
            require(token.isNotEmpty() && token.toByteArray(Charsets.UTF_8).size <= 128 && token.none(Char::isISOControl))
        }
        fun json() = JSONObject().put("sid", sid).put("route_revision", revision)
            .put("route_generation", generation).put("route_token", token)
        override fun toString() = "Identity(redacted)"
    }

    internal class Fragment(val header: String, val binary: ByteArray) {
        fun frame(sequence: Int) = ProtocolCodec.navigationSceneFragment(sequence, header, binary)
        override fun toString() = "Fragment(bytes=" + binary.size + ")"
    }
    internal class Encoded(val fragments: List<Fragment>, private val identity: Identity,
                           private val sceneId: String, private val hash: String, private val size: Int,
                           private val crc: Long) {
        /** Only a correlated 0x3D response is a scene commit; not physical visibility. */
        fun committed(message: ProtocolCodec.Message): Boolean {
            if (message.version != 1 || message.type != 0x3d || message.mode != 1L ||
                message.sequence != 0L || message.audio?.isNotEmpty() == true) return false
            val json = message.json ?: return false
            if (json.length !in 1..8192) return false
            val body = runCatching { JSONObject(json) }.getOrNull() ?: return false
            fun number(key: String, expected: Long): Boolean = when(val value = body.opt(key)) {
                is Int -> value.toLong() == expected
                is Long -> value == expected
                else -> false
            }
            return body.opt("sid") == identity.sid && number("route_revision", identity.revision) &&
                number("route_generation", identity.generation) && body.opt("route_token") == identity.token &&
                body.opt("scene_id") == sceneId && body.opt("route_hash") == hash &&
                number("total_len", size.toLong()) && number("crc32", crc) && number("code", 0)
        }
        override fun toString() = "Encoded(fragments=" + fragments.size + ")"
    }
    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    /** Firmware hashes decimal travel_mode + LF + overview polyline ASCII. */
    fun routeHash(travelMode: Int, overview: Geometry): String {
        require(travelMode in 1..3)
        return String.format(Locale.ROOT, "%08x", crc32(("$travelMode\n" + overview.polyline6).toByteArray(Charsets.UTF_8)))
    }
    /** Encode only caller-provided genuine geometry and session identity.
     * Numeric travel mode is a negotiated wire value, not an inferred mode. */
    fun encode(identity: Identity, sceneId: String, travelMode: Int,
               overview: Geometry, local: Geometry): Encoded {
        require(sceneId.isNotEmpty() && sceneId.toByteArray(Charsets.UTF_8).size <= 64 && sceneId.none(Char::isISOControl))
        val hash = routeHash(travelMode, overview)
        fun common() = identity.json().put("scene_id", sceneId).put("route_hash", hash)
        val body = common().put("profile", "ROUTE_SCENE_V1").put("travel_mode", travelMode)
            .put("overview_polyline6", overview.polyline6).put("local_polyline6", local.polyline6)
            .put("overview_bounds", overview.bounds.json()).put("local_bounds", local.bounds.json())
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX_BODY)
        val crc = crc32(body)
        val count = (body.size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE
        val parts = (0 until count).map { index ->
            val offset = index * FRAGMENT_SIZE
            val bytes = body.copyOfRange(offset, minOf(offset + FRAGMENT_SIZE, body.size))
            val header = common().put("total_len", body.size).put("fragment_index", index)
                .put("fragment_count", count).put("fragment_offset", offset)
                .put("fragment_len", bytes.size).put("crc32", crc).toString()
            Fragment(header, bytes)
        }
        return Encoded(parts, identity, sceneId, hash, body.size, crc)
    }
}
