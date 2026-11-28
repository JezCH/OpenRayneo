// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/** Official Kakao Map REST API, explicitly enabled by the user. The key and
 * coordinates never enter logs, exports or the repository. No automatic paid
 * API configuration, retries or redirection to third-party hosts. */
internal object KakaoNativeRouteSettings {
    data class Config(val enabled: Boolean = false, val key: String = "") {
        val configured get() = enabled && key.matches(Regex("[A-Za-z0-9_-]{16,128}"))
        override fun toString() = "KakaoNativeRouteSettings(redacted)"
    }
    private fun store(context: Context) = AppSettings(context, "kakao_native_navigation")
    fun read(context: Context): Config = store(context).readStored().let {
        Config(it.optBoolean("enabled"), it.optString("key"))
    }
    fun save(context: Context, config: Config) {
        require(!config.enabled || config.configured) { "유효한 카카오 REST API 키를 입력하세요." }
        store(context).save(JSONObject().put("enabled", config.enabled).put("key", config.key))
    }
}

internal object KakaoWalkingRoute {
    class Failure(message: String) : IllegalStateException(message)
    private fun fail(message: String): Nothing = throw Failure(message)
    private fun number(value: Any?): Double = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
        ?: fail("응답 수치 형식 오류")
    private fun distance(a: NaverWalkingCourse.Point, b: NaverWalkingCourse.Point): Double {
        val lat = Math.toRadians(b.latitude-a.latitude)
        val lon = Math.toRadians(b.longitude-a.longitude)
        return 12_742_000.0 * asin(sqrt((sin(lat/2).pow(2) +
            cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)).coerceIn(0.0,1.0)))
    }
    private fun point(x: Any?, y: Any?): NaverWalkingCourse.Point {
        val lon = number(x); val lat = number(y)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) fail("응답 좌표 범위 오류")
        return NaverWalkingCourse.Point(lat, lon)
    }
    private fun query(key: String, path: String, params: Map<String, String>): String {
        require(key.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        val suffix = params.entries.joinToString("&") { (k,v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val connection = URL("https://dapi.kakao.com/$path?$suffix").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7_000; connection.readTimeout = 7_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "KakaoAK $key")
            val status = connection.responseCode
            if (status != 200) fail(when(status) {
                401, 403 -> "API 인증 또는 카카오맵 활성화 확인 (HTTP $status)"
                429 -> "API 쿼터 또는 호출 제한 (HTTP 429)"
                else -> "카카오 API 요청 실패 (HTTP $status)"
            })
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (output.size()+n > 1_048_576) fail("경로 응답 크기 초과")
                    output.write(bytes, 0, n)
                }
                output.toString("UTF-8")
            }
        } catch (error: Failure) { throw error }
        catch (_: Exception) { fail("카카오 API 연결·응답 실패") }
        finally { connection.disconnect() }
    }

    private fun resolve(key: String, destination: String, start: NaverWalkingCourse.Point): NaverWalkingCourse.Point {
        runCatching { OfflineWalkingRoute.coordinate(destination) }.getOrNull()?.let { return it }
        if (Regex("""(?:로|길|동|리|가)\s+\d""").containsMatchIn(destination)) {
            val address = JSONObject(query(key, "v2/local/search/address.json", mapOf("query" to destination, "size" to "10")))
            val docs = address.getJSONArray("documents")
            if (address.getJSONObject("meta").optBoolean("is_end", false) && docs.length() == 1) {
                val match = docs.getJSONObject(0)
                val x = match.optString("x").toDoubleOrNull() ?: fail("주소 좌표 오류")
                val y = match.optString("y").toDoubleOrNull() ?: fail("주소 좌표 오류")
                return point(x, y)
            }
            fail("주소 검색 결과가 없거나 여러 개입니다. 정확한 주소 또는 좌표를 입력하세요.")
        }
        val raw = query(key, "v2/local/search/keyword.json", mapOf("query" to destination,
            "x" to start.longitude.toString(), "y" to start.latitude.toString(), "size" to "15"))
        val root = JSONObject(raw)
        val docs = root.getJSONArray("documents")
        val exact = (0 until docs.length()).map { docs.getJSONObject(it) }.filter { doc ->
            destination == doc.optString("place_name") || destination == doc.optString("address_name") ||
                destination == doc.optString("road_address_name")
        }
        val complete = root.getJSONObject("meta").optBoolean("is_end", false)
        val match = when {
            complete && exact.size == 1 -> exact.single()
            complete && docs.length() == 1 -> docs.getJSONObject(0)
            else -> fail("목적지 검색 결과가 없거나 여러 개입니다. 정확한 장소 또는 좌표를 입력하세요.")
        }
        val x = match.optString("x").toDoubleOrNull() ?: fail("목적지 좌표 오류")
        val y = match.optString("y").toDoubleOrNull() ?: fail("목적지 좌표 오류")
        return point(x,y)
    }

    fun calculate(key: String, start: NaverWalkingCourse.Point, destination: String): OfflineWalkingRoute.Route {
        val end = resolve(key, destination, start)
        if (distance(start,end) !in 10.0..50_000.0) fail("도보 경로 요청 범위 10m~50km")
        val raw = query(key, "v2/routing/walk", mapOf(
            "start_x" to start.longitude.toString(), "start_y" to start.latitude.toString(),
            "end_x" to end.longitude.toString(), "end_y" to end.latitude.toString(),
            "input_coord" to "WGS84", "output_coord" to "WGS84"))
        return try { parse(raw,start,end) } catch (error: Failure) { throw error }
        catch (_: Exception) { fail("카카오 도보 경로 응답 규격 오류") }
    }

    // The documented API returns instructions, not BRouter or RayNeo turn codes.
    // Only unambiguous Korean commands map to already verified stock symbols;
    // all other instructions remain text-only (no directional icon).
    internal fun maneuver(text: String): Int {
        val directions = listOf(
            Regex("""(?:^|\s)급좌회전(?:$|[ ,.]|하세요)""") to 5,
            Regex("""(?:^|\s)급우회전(?:$|[ ,.]|하세요)""") to 8,
            Regex("""(?:^|\s)좌회전(?:$|[ ,.]|하세요)""") to 4,
            Regex("""(?:^|\s)우회전(?:$|[ ,.]|하세요)""") to 7,
            Regex("""(?:^|\s)직진(?:$|[ ,.]|하세요)""") to 2
        ).filter { it.first.containsMatchIn(text) }
        return directions.singleOrNull()?.second ?: 0
    }
    private fun instruction(raw: String): String {
        val text = raw.filterNot(Char::isISOControl).trim()
        if (text.isBlank()) return ""
        return buildString {
            var count = 0
            for (cp in text.codePoints().toArray()) {
                val value = String(Character.toChars(cp))
                val n = value.toByteArray(Charsets.UTF_8).size
                if (count + n > 256) break
                append(value); count += n
            }
        }
    }

    /** Documented route.properties/legs/steps/path.points WGS84 contract.
     * Interpolate provider step time only along its own geometry. No invented
     * off-route connection, arrival, maneuver enum or provider identity. */
    fun parse(raw: String, start: NaverWalkingCourse.Point, end: NaverWalkingCourse.Point): OfflineWalkingRoute.Route {
        if(raw.length !in 1..1_048_576) fail("경로 응답 크기 오류")
        val root = JSONObject(raw)
        val status = root.optString("status")
        // Official success example omits status; require a valid full route.
        if ((root.has("status") && status != "OK") || !root.has("route")) fail("경로 없음 또는 요청 거부" + when(status) {
            "SAME_POINT", "START_LINK_NOT_FOUND", "END_LINK_NOT_FOUND", "TOO_MANY_SEARCH_LINK",
            "TOO_FAR_AWAY", "ROUTE_RESULT_NOT_FOUND" -> " ($status)"
            else -> ""
        })
        val route = root.getJSONObject("route")
        val props = route.getJSONObject("properties")
        val total = number(props.opt("totalTime"))
        val declaredDistance = number(props.opt("totalDistance"))
        if (total !in 1.0..604800.0 || declaredDistance !in 1.0..200_000.0) fail("경로 거리·시간 범위 오류")
        val points = ArrayList<NaverWalkingCourse.Point>()
        val times = ArrayList<Double>()
        val turns = ArrayList<OfflineWalkingRoute.Turn>()
        var elapsed = 0.0
        val legs = route.getJSONArray("legs")
        if (legs.length() !in 1..6) fail("경로 구간 수 오류")
        for (i in 0 until legs.length()) {
            val steps = legs.getJSONObject(i).getJSONArray("steps")
            if (steps.length() !in 1..10_000) fail("경로 단계 수 오류")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                val sp = step.getJSONObject("properties")
                val secs = number(sp.opt("time"))
                val meters = number(sp.opt("distance"))
                if (secs !in 0.0..604800.0 || meters !in 0.0..200_000.0) fail("단계 거리·시간 오류")
                val path = step.getJSONObject("path").getJSONArray("points")
                if (path.length() !in 1..30_000) fail("단계 좌표 수 오류")
                val coords = (0 until path.length()).map { k ->
                    val row = path.getJSONArray(k)
                    if (row.length() != 2) fail("경로 좌표 형식 오류")
                    point(row.opt(0),row.opt(1))
                }
                if (points.isNotEmpty() && distance(points.last(),coords.first()) > 3.0)
                    fail("경로 단계 사이의 좌표가 끊어져 있습니다.")
                val prefix = DoubleArray(coords.size)
                for (k in 1 until coords.size) {
                    val length = distance(coords[k-1], coords[k])
                    if (length > 1000.0) fail("연속 경로 좌표 간격 오류")
                    prefix[k] = prefix[k-1]+length
                }
                val firstIndex = if (points.lastOrNull() == coords.first()) points.lastIndex else points.size
                val guidance = instruction(sp.optString("guidance"))
                if (guidance.isNotBlank())
                    turns.add(OfflineWalkingRoute.Turn(firstIndex, 0, 0, meters, 0, guidance, maneuver(guidance)))
                for(k in coords.indices) {
                    val time = elapsed + if(prefix.last()>0) secs*prefix[k]/prefix.last() else 0.0
                    if(points.lastOrNull()==coords[k]) { times[times.lastIndex] = maxOf(times.last(),time); continue }
                    if(points.size>=30_000) fail("전체 경로 좌표 수 초과")
                    points.add(coords[k]); times.add(time)
                }
                elapsed += secs
                if (elapsed > 604800.0) fail("전체 시간 범위 오류")
                if (times.isNotEmpty()) times[times.lastIndex] = elapsed
            }
        }
        if(points.size<2 || distance(start,points.first())>100.0 || distance(end,points.last())>100.0)
            fail("요청한 출발·도착점과 경로 좌표가 일치하지 않습니다.")
        val course = NaverWalkingCourse.Course(points.toList())
        if(course.lengthMeters !in 1.0..200_000.0) fail("유효하지 않은 경로 길이")
        val timeValues = times.takeIf { elapsed > 0 && abs(elapsed-total)<=maxOf(10.0,total*.05) }
        return OfflineWalkingRoute.Route(course, turns, timeValues, total, "kakao", end)
    }
}
