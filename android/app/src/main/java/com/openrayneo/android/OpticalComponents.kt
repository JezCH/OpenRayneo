// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Opaque information plane; no glow behind functional text. */
@Composable
internal fun OpticalPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Static original brand artwork, not an indication of AI inference or device readiness. */
@Composable
internal fun OpticalSignature(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Image(
        painter = painterResource(if (dark) R.drawable.optical_idle_dark else R.drawable.optical_idle_light),
        contentDescription = null,
        modifier = modifier,
    )
}

@Composable
internal fun OpticalActivityHeader(activity: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showArt = maxWidth >= 240.dp && LocalDensity.current.fontScale <= 1.3f
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("현재 활동", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(activity, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }
            if (showArt) OpticalSignature(Modifier.size(80.dp))
        }
    }
}

/** Reflow instead of shrinking labels or hit areas when space is constrained. */
@Composable
internal fun OpticalQuickPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun OpticalQuickTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, click: () -> Unit) {
    Card(
        onClick = click,
        modifier = modifier.heightIn(min = 80.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        // Keep the icon beside the label: the six home destinations should not
        // spend a whole extra line on ornament at ordinary font sizes.
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Active means observed runtime, not merely configuration or readiness.
@Composable
internal fun OpticalActiveInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFFA5E7B6) else Color(0xFF21663C)

/** Presentation only: no inference about streaming, actual execution success or microphone capture. */
internal enum class OpticalAiPhase { UNAVAILABLE, APPROVAL, REQUEST, SETUP, RESPONSE, READY }

internal fun opticalAiPhase(
    available: Boolean,
    pendingApproval: Boolean,
    running: Boolean,
    ready: Boolean,
    hasAnswer: Boolean,
): OpticalAiPhase = when {
    !available -> OpticalAiPhase.UNAVAILABLE
    pendingApproval -> OpticalAiPhase.APPROVAL
    running -> OpticalAiPhase.REQUEST
    !ready -> OpticalAiPhase.SETUP
    hasAnswer -> OpticalAiPhase.RESPONSE
    else -> OpticalAiPhase.READY
}

/**
 * A small optical aperture: a single 180ms transition on observed request-state
 * changes, never a looping thinking animation or a progress percentage.
 * Compose respects the system animation duration scale. Text changes immediately.
 */
@Composable
internal fun OpticalAiIndicator(phase: OpticalAiPhase, modifier: Modifier = Modifier) {
    val alignment by animateFloatAsState(
        targetValue = if (phase == OpticalAiPhase.REQUEST) 1f else 0f,
        animationSpec = tween(180),
        label = "optical-request-alignment",
    )
    val color = when (phase) {
        OpticalAiPhase.APPROVAL -> MaterialTheme.colorScheme.tertiary
        OpticalAiPhase.REQUEST, OpticalAiPhase.RESPONSE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier.size(32.dp)) {
        val stroke = 1.75.dp.toPx()
        val r = size.minDimension * .35f
        val c = center
        val a = if (phase == OpticalAiPhase.REQUEST || phase == OpticalAiPhase.READY) alignment else 0f
        when (phase) {
            OpticalAiPhase.APPROVAL -> {
                val points = listOf(Offset(c.x, c.y-r), Offset(c.x+r,c.y), Offset(c.x,c.y+r), Offset(c.x-r,c.y))
                points.indices.forEach { i -> drawLine(color, points[i], points[(i+1)%4], stroke, StrokeCap.Round) }
                drawCircle(color, stroke, c)
            }
            OpticalAiPhase.RESPONSE -> {
                drawLine(color, Offset(c.x-r,c.y-r*.45f), Offset(c.x+r,c.y-r*.45f), stroke, StrokeCap.Round)
                drawLine(color, Offset(c.x-r,c.y+r*.45f), Offset(c.x+r*.35f,c.y+r*.45f), stroke, StrokeCap.Round)
            }
            OpticalAiPhase.UNAVAILABLE, OpticalAiPhase.SETUP -> {
                drawCircle(color, r, c, style = Stroke(stroke))
                drawLine(color, Offset(c.x-r*.55f,c.y+r*.55f), Offset(c.x+r*.55f,c.y-r*.55f), stroke, StrokeCap.Round)
            }
            else -> {
                val d = r * (.42f - .27f * a)
                val arcSize = Size(2*r, 2*r)
                drawArc(color, 115f, 130f, false, Offset(c.x-r+d,c.y-r), arcSize, style=Stroke(stroke, cap=StrokeCap.Round))
                drawArc(color, -65f, 130f, false, Offset(c.x-r-d,c.y-r), arcSize, style=Stroke(stroke, cap=StrokeCap.Round))
                drawCircle(color, stroke, c)
            }
        }
    }
}

@Composable
internal fun OpticalAiStatus(phase: OpticalAiPhase, detail: String, modifier: Modifier = Modifier) {
    val label = when (phase) {
        OpticalAiPhase.UNAVAILABLE -> "서비스 상태 미확인"
        OpticalAiPhase.APPROVAL -> "사용자 확인 필요"
        OpticalAiPhase.REQUEST -> "AI 요청 처리 중"
        OpticalAiPhase.SETUP -> "연결 또는 설정 확인"
        OpticalAiPhase.RESPONSE -> "답변 있음"
        OpticalAiPhase.READY -> "질문 입력 가능"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OpticalAiIndicator(phase)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall,
                color = if (phase == OpticalAiPhase.APPROVAL) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            if (detail.isNotBlank() && detail != label) Note(detail)
        }
    }
}
