// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlin.math.cos

/**
 * Render the actual user-selected route coordinates into a stock-sized PNG.
 * No basemap, live position, turn instruction, ETA, or route/provider identity
 * is invented. A full-course thumbnail is not a moving mini-map.
 * Session.submitRaster still requires a correlated view and negotiated limits.
 */
internal object NativeCourseRaster {
    class Preview(val mini: ByteArray, val overview: ByteArray)

    fun preview(course: NaverWalkingCourse.Course): Preview = Preview(
        render(course, NativeRasterFrame.View.MINI),
        render(course, NativeRasterFrame.View.OVERVIEW))

    fun render(course: NaverWalkingCourse.Course, view: NativeRasterFrame.View): ByteArray {
        val width = if (view == NativeRasterFrame.View.MINI) 184 else 540
        val height = if (view == NativeRasterFrame.View.MINI) 90 else 220
        val upperBound = if (view == NativeRasterFrame.View.MINI) 65_536 else 262_144
        // North up, longitude unwrapped across the date line. Fit the real
        // geometry into the available aspect ratio, not a square viewport.
        val points = course.points
        require(points.size in 2..30_000)
        val centerLatitude = points.map { it.latitude }.average()
        val longitudeScale = cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.000001)
        var previous = points.first().longitude
        var longitude = previous
        val xy = points.mapIndexed { index, point ->
            if (index > 0) {
                var delta = point.longitude - previous
                if (delta > 180.0) delta -= 360.0
                if (delta < -180.0) delta += 360.0
                longitude += delta
                previous = point.longitude
            }
            longitude * longitudeScale to point.latitude
        }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val margin = if (view == NativeRasterFrame.View.MINI) 9.0 else 14.0
        val scale = minOf(
            (width - 2 * margin) / (maxX - minX).coerceAtLeast(1e-12),
            (height - 2 * margin) / (maxY - minY).coerceAtLeast(1e-12))
        fun pixel(point: Pair<Double, Double>) =
            (width / 2.0 + (point.first - (minX + maxX) / 2.0) * scale).toFloat() to
                (height / 2.0 - (point.second - (minY + maxY) / 2.0) * scale).toFloat()
        val path = Path()
        xy.forEachIndexed { index, point ->
            val (x, y) = pixel(point)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            fun draw(antialias: Boolean): ByteArray {
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply {
                    isAntiAlias = antialias
                    color = Color.WHITE
                    strokeWidth = if (view == NativeRasterFrame.View.MINI) 2f else 3f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    style = Paint.Style.STROKE
                }
                canvas.drawPath(path, paint)
                // Circle = recorded start; square = recorded end. No moving marker.
                val (sx, sy) = pixel(xy.first())
                val (ex, ey) = pixel(xy.last())
                val radius = if (view == NativeRasterFrame.View.MINI) 4f else 6f
                canvas.drawCircle(sx, sy, radius, paint)
                canvas.drawRect(ex - radius, ey - radius, ex + radius, ey + radius, paint)
                return ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            }
            // Dense routes can produce larger antialiased images. Remove only
            // subpixel antialiasing if needed, not actual route segments.
            val first = draw(true)
            return (if (first.size <= upperBound) first else draw(false)).also {
                require(it.size in 1..upperBound) { "순정 경로 PNG 크기 한도 초과" }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
