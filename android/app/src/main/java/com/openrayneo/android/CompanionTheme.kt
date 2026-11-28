// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Pine = Color(0xFF256B52)
internal val SignalGreen = Color(0xFFB8EFCD)
internal val LensBlack = Color(0xFF13251D)
// Optical V3: opaque, independently checked foreground/background roles.
private val Day =
    lightColorScheme(
        primary = Color(0xFF005D80),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD9EDF7),
        onPrimaryContainer = Color(0xFF12384C),
        secondary = Color(0xFF526275),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE4EAF1),
        onSecondaryContainer = Color(0xFF26394D),
        tertiary = Color(0xFF805000),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFEAC8),
        onTertiaryContainer = Color(0xFF4B2E00),
        error = Color(0xFFA3293D),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFCE0E4),
        onErrorContainer = Color(0xFF731A2A),
        background = Color(0xFFF2F6FA),
        onBackground = Color(0xFF152333),
        surface = Color(0xFFF2F6FA),
        onSurface = Color(0xFF152333),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFE9EFF5),
        surfaceContainerHigh = Color(0xFFE0E8F0),
        surfaceContainerHighest = Color(0xFFD7E1EB),
        surfaceVariant = Color(0xFFE0E8F0),
        onSurfaceVariant = Color(0xFF526275),
        outline = Color(0xFF6C7F92),
        outlineVariant = Color(0xFFC7D3DF),
        inverseSurface = Color(0xFF233347),
        inverseOnSurface = Color(0xFFF0F6FF),
        inversePrimary = Color(0xFF83DFFF),
        surfaceTint = Color(0xFF005D80),
        scrim = Color(0xFF000000),
    )
private val Night =
    darkColorScheme(
        primary = Color(0xFF83DFFF),
        onPrimary = Color(0xFF091923),
        primaryContainer = Color(0xFF173B4D),
        onPrimaryContainer = Color(0xFFC9F0FF),
        secondary = Color(0xFFA7B6CA),
        onSecondary = Color(0xFF182737),
        secondaryContainer = Color(0xFF253344),
        onSecondaryContainer = Color(0xFFE2EAF4),
        tertiary = Color(0xFFFFD28B),
        onTertiary = Color(0xFF402900),
        tertiaryContainer = Color(0xFF4D3518),
        onTertiaryContainer = Color(0xFFFFE5BA),
        error = Color(0xFFFFA7AD),
        onError = Color(0xFF561923),
        errorContainer = Color(0xFF53232D),
        onErrorContainer = Color(0xFFFFDADD),
        background = Color(0xFF090E17),
        onBackground = Color(0xFFF0F6FF),
        surface = Color(0xFF090E17),
        onSurface = Color(0xFFF0F6FF),
        surfaceContainerLowest = Color(0xFF090E17),
        surfaceContainerLow = Color(0xFF111923),
        surfaceContainer = Color(0xFF141E2C),
        surfaceContainerHigh = Color(0xFF1D2A3A),
        surfaceContainerHighest = Color(0xFF253344),
        surfaceVariant = Color(0xFF253344),
        onSurfaceVariant = Color(0xFFA7B6CA),
        outline = Color(0xFF657D94),
        outlineVariant = Color(0xFF344457),
        inverseSurface = Color(0xFFDFEAF4),
        inverseOnSurface = Color(0xFF152333),
        inversePrimary = Color(0xFF005D80),
        surfaceTint = Color(0xFF83DFFF),
        scrim = Color(0xFF000000),
    )

@Composable
internal fun CompanionTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = appearance == "dark" || appearance == "system" && isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode)
        SideEffect {
            (view.context as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle =
                    if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        ),
                navigationBarStyle =
                    if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        ),
            )
        }
    MaterialTheme(
        colorScheme = if (dark) Night else Day,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
        typography =
            Typography().let {
                it.copy(
                    headlineLarge = it.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
                    headlineMedium = it.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
                    headlineSmall = it.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    titleLarge = it.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    titleMedium = it.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
                    titleSmall = it.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                    bodyLarge = it.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
                    bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
                    bodySmall = it.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    labelLarge = it.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    labelMedium = it.labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
                )
            },
        content = content,
    )
}

@Composable
internal fun GlassesArtwork(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.semantics { contentDescription = "RayNeo 스마트 안경 윤곽" }) {
        val w = size.width
        val h = size.height
        drawCircle(color.copy(alpha = .055f), minOf(w * .32f, h * .46f), Offset(w * .5f, h * .5f))
        val stroke = Stroke(3.dp.toPx())
        drawRoundRect(
            color,
            Offset(w * .14f, h * .4f),
            Size(w * .31f, h * .34f),
            androidx.compose.ui.geometry.CornerRadius(w * .05f),
            style = stroke,
        )
        drawRoundRect(
            color,
            Offset(w * .55f, h * .4f),
            Size(w * .31f, h * .34f),
            androidx.compose.ui.geometry.CornerRadius(w * .05f),
            style = stroke,
        )
        val bridge =
            Path().apply {
                moveTo(w * .45f, h * .49f)
                quadraticTo(w * .5f, h * .38f, w * .55f, h * .49f)
            }
        drawPath(bridge, color, style = stroke)
        drawLine(color, Offset(w * .14f, h * .46f), Offset(w * .04f, h * .22f), stroke.width)
        drawLine(color, Offset(w * .86f, h * .46f), Offset(w * .96f, h * .22f), stroke.width)
        drawLine(
            color.copy(alpha = .3f),
            Offset(w * .2f, h * .49f),
            Offset(w * .31f, h * .49f),
            2.dp.toPx(),
        )
        drawCircle(color, 2.8.dp.toPx(), Offset(w * .8f, h * .46f))
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(18.dp).background(MaterialTheme.colorScheme.primary))
        Text(text, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() })
        HorizontalDivider(Modifier.weight(.18f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ContentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = OpticalFrameShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
internal fun ToolButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick, modifier.heightIn(min = 50.dp), enabled, shape = OpticalControlShape, content = content)
}

@Composable
internal fun ToolOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled,
        shape = OpticalControlShape,
        content = content,
    )
}
