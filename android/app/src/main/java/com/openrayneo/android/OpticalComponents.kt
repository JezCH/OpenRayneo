// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        modifier = modifier.heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Active means observed runtime, not merely configuration or readiness.
@Composable
internal fun OpticalActiveInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFFA5E7B6) else Color(0xFF21663C)
