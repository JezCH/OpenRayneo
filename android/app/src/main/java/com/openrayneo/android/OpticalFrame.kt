// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A single optical frame language for the five real destinations. */
internal val OpticalFrameShape = CutCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
internal val OpticalControlShape = CutCornerShape(topStart = 3.dp, topEnd = 12.dp, bottomEnd = 3.dp, bottomStart = 12.dp)
private val FrameTabs = listOf("홈", "AI", "기억", "도구", "기기")

@Composable
internal fun OpticalFrameHeader(title: String, isDetail: Boolean, compact: Boolean, back: () -> Unit, settings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dense = compact || LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().background(colors.background)
        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (dense) 48.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isDetail) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
            else OpticalFrameGlyph(0, colors.primary, Modifier.size(if (dense) 22.dp else 28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (!dense) Text("OPENRAYNEO", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, letterSpacing = 2.sp)
                Text(title, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            if (!isDetail) IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "설정") }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** No Material navigation pill: a flat optical instrument strip with a selected edge. */
@Composable
internal fun OpticalFrameNavigation(selected: Int, pending: Int, select: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow)
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 6.dp)) {
            FrameTabs.forEachIndexed { index, label ->
                val active = selected == index
                val ink = if (active) colors.primary else colors.onSurfaceVariant
                Column(Modifier.weight(1f).heightIn(min = 60.dp)
                    .selectable(selected = active, onClick = { select(index) }, role = Role.Tab),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(34.dp).height(2.dp)
                        .background(if (active) colors.primary else Color.Transparent))
                    BadgedBox(badge = {
                        if (index == 1 && pending > 0) Badge { Text(pending.toString()) }
                    }) {
                        OpticalFrameGlyph(index, ink, Modifier.padding(top = 3.dp).size(24.dp))
                    }
                    Text(label, color = ink, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }
}

/** Original line glyphs: aperture, AI, archive, instrument grid and glasses. */
@Composable
internal fun OpticalFrameGlyph(index: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = size.minDimension * .068f
        fun p(x: Float, y: Float) = Offset(x*w, y*h)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1,y1), p(x2,y2), stroke, StrokeCap.Round)
        fun outline(vararg points: Pair<Float, Float>) {
            val path = Path().apply {
                moveTo(points.first().first*w, points.first().second*h)
                points.drop(1).forEach { lineTo(it.first*w,it.second*h) }
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when (index) {
            0 -> {
                outline(.5f to .08f,.92f to .5f,.5f to .92f,.08f to .5f,.5f to .08f)
                drawCircle(color, w*.13f, center, style=Stroke(stroke))
                line(.5f,.08f,.5f,.28f); line(.5f,.72f,.5f,.92f)
            }
            1 -> {
                drawArc(color, 120f, 120f, false, p(.08f,.10f), Size(w*.78f,h*.8f), style=Stroke(stroke, cap=StrokeCap.Round))
                drawArc(color, -60f, 120f, false, p(.14f,.10f), Size(w*.78f,h*.8f), style=Stroke(stroke, cap=StrokeCap.Round))
                drawCircle(color,w*.09f,center)
            }
            2 -> {
                outline(.13f to .28f,.5f to .08f,.87f to .28f,.5f to .48f,.13f to .28f)
                outline(.13f to .49f,.5f to .69f,.87f to .49f)
                outline(.13f to .7f,.5f to .9f,.87f to .7f)
            }
            3 -> {
                for (x in listOf(.12f,.58f)) for (y in listOf(.12f,.58f))
                    outline(x to y,(x+.3f) to y,(x+.3f) to (y+.3f),x to (y+.3f),x to y)
            }
            else -> {
                outline(.04f to .35f,.12f to .69f,.43f to .69f,.47f to .35f,.04f to .35f)
                outline(.53f to .35f,.57f to .69f,.88f to .69f,.96f to .35f,.53f to .35f)
                line(.47f,.39f,.53f,.39f); line(.04f,.35f,.04f,.22f); line(.96f,.35f,.96f,.22f)
            }
        }
    }
}

/** Primary display area: deliberate optical geometry, not a generic rounded card. */
@Composable
internal fun OpticalHeroPanel(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(OpticalFrameShape)
        .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerLow)))) {
        Row(Modifier.fillMaxWidth().height(3.dp)) {
            Box(Modifier.weight(.28f).fillMaxHeight().background(colors.primary))
            Box(Modifier.weight(.72f).fillMaxHeight().background(colors.outlineVariant))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun OpticalWorkspaceHeading(
    title: String, detail: String, glyph: Int,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(detail, style = MaterialTheme.typography.labelMedium, color = detailColor)
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        if (LocalDensity.current.fontScale <= 1.3f)
            OpticalFrameGlyph(glyph, colors.primary, Modifier.size(36.dp))
    }
}
