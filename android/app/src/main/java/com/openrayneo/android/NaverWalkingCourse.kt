// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

/** Explicitly selected GPX, not an active route feed or a proof of NAVER provenance.
 * Geometry only: no inferred maneuver, ETA, current position or route recalculation. */
internal object NaverWalkingCourse {
    const val MAX_BYTES = 1_048_576
    private const val MAX_POINTS = 30_000
    private val gpxNamespaces = setOf("", "http://www.topografix.com/GPX/1/0", "http://www.topografix.com/GPX/1/1")
    data class Point(val latitude: Double, val longitude: Double) {
        override fun toString() = "Point(redacted)"
    }
    class Course internal constructor(val points: List<Point>) {
        val lengthMeters: Double = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        // Standard six-decimal encoded polyline, not a completed RayNeo route-scene message.
        fun polyline6(): String = encodePolyline6(points)
        fun nativeGeometry(): NativeRouteScene.Geometry? = NativeRouteScene.geometry(points)
        override fun toString() = "Course(points=${points.size})"
    }
    internal fun encodePolyline6(points: List<Point>): String = buildString {
            var lastLat = 0L; var lastLon = 0L
            fun appendDelta(delta: Long) {
                var value = if (delta < 0) (-delta * 2 - 1) else delta * 2
                while (value >= 32) {
                    append(((value and 31) or 32).toInt().plus(63).toChar())
                    value = value shr 5
                }
                append((value.toInt() + 63).toChar())
            }
            for (point in points) {
                val lat = (point.latitude * 1_000_000).roundToLong()
                val lon = (point.longitude * 1_000_000).roundToLong()
                appendDelta(lat - lastLat); appendDelta(lon - lastLon)
                lastLat = lat; lastLon = lon
            }
    }
    private fun distance(a: Point, b: Point): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun read(input: InputStream): Course {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(count > 0 && buffer.size() + count <= MAX_BYTES) { "1MB 이하의 GPX 파일만 지원합니다." }
            buffer.write(chunk, 0, count)
        }
        val data = buffer.toByteArray()
        require(data.size in 1..MAX_BYTES) { "1MB 이하의 GPX 파일만 지원합니다." }
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
        // Reader fixes encoding; XML DTD syntax is case-sensitive. No external resources.
        require(!xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY")) { "DTD가 있는 GPX는 지원하지 않습니다." }
        val points = ArrayList<Point>()
        val stack = ArrayList<Pair<String, String>>()
        var namespace: String? = null
        var tracks = 0; var segments = 0; var routes = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity")
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                require(stack.size < 64) { "GPX 구조가 너무 복잡합니다." }
                if (stack.isEmpty()) {
                    require(localName == "gpx" && uri in gpxNamespaces) { "GPX 파일 형식이 아닙니다." }
                    namespace = uri
                }
                stack.add(uri to localName)
                if (stack.any { it.first != namespace }) return
                val path = stack.map { it.second }
                when (path) {
                    listOf("gpx", "trk") -> { tracks++; require(tracks == 1 && routes == 0) { "한 개의 연속 코스만 선택하세요." } }
                    listOf("gpx", "trk", "trkseg") -> { segments++; require(segments == 1) { "끊어진 코스는 지원하지 않습니다." } }
                    listOf("gpx", "rte") -> { routes++; require(routes == 1 && tracks == 0) { "한 개의 연속 코스만 선택하세요." } }
                    listOf("gpx", "trk", "trkseg", "trkpt"), listOf("gpx", "rte", "rtept") -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        require(lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) { "유효하지 않은 GPX 좌표입니다." }
                        require(points.size < MAX_POINTS) { "GPX 좌표가 너무 많습니다." }
                        points.add(Point(lat, lon))
                    }
                }
            }
            override fun endElement(uri: String, localName: String, qName: String) { stack.removeAt(stack.lastIndex) }
        }
        val parser = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }.newSAXParser().xmlReader
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        parser.entityResolver = handler
        parser.contentHandler = handler
        parser.errorHandler = handler
        parser.parse(InputSource(StringReader(xml)))
        require(points.size >= 2 && (tracks == 1 && segments == 1 || routes == 1)) { "연속 경로 좌표가 없는 GPX입니다." }
        val course = Course(points.toList())
        require(course.lengthMeters.isFinite() && course.lengthMeters > 0.0) { "길이가 없는 GPX 코스입니다." }
        return course
    }
}
