/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.presentation.components.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blends the leading/trailing edges of a horizontally scrollable row into whatever sits
 * behind it, fading an edge only while that side still has hidden content.
 *
 * Pass [lazyListState] for a `LazyRow`, or [scrollState] for `Modifier.horizontalScroll`.
 * [fadeWidth] should roughly match the row's gutter (padding + contentPadding + spacing).
 */
fun Modifier.horizontalEdgeBlend(
    lazyListState: LazyListState? = null,
    scrollState: ScrollState? = null,
    fadeWidth: Dp = 20.dp
): Modifier = composed {
    val canScrollBackward = lazyListState?.canScrollBackward
        ?: scrollState?.canScrollBackward
        ?: false
    val canScrollForward = lazyListState?.canScrollForward
        ?: scrollState?.canScrollForward
        ?: false

    val startFade by animateDpAsState(
        targetValue = if (canScrollBackward) fadeWidth else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "HorizontalEdgeBlendStart"
    )

    val endFade by animateDpAsState(
        targetValue = if (canScrollForward) fadeWidth else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "HorizontalEdgeBlendEnd"
    )

    if (startFade <= 0.dp && endFade <= 0.dp) {
        this
    } else {
        this
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()

                if (startFade > 0.dp) {
                    val width = startFade.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = width
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }

                if (endFade > 0.dp) {
                    val width = endFade.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = size.width - width,
                            endX = size.width
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    }
}
