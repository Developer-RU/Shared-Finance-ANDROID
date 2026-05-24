package com.sharedfinance.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.min

@Composable
fun PatternLockView(
    pattern: List<Int>,
    onPatternChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color,
    inactiveColor: Color
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val side = min(maxWidth.value, maxHeight.value)
        val step = side / 4f
        val centers = remember(step) {
            List(9) { index ->
                val row = index / 3
                val col = index % 3
                Offset(
                    x = (col + 1) * step,
                    y = (row + 1) * step
                )
            }
        }
        val threshold = step * 0.45f

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(pattern) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            nodeAt(offset, centers, threshold)?.let { node ->
                                if (!pattern.contains(node)) {
                                    onPatternChange(pattern + node)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            nodeAt(change.position, centers, threshold)?.let { node ->
                                if (!pattern.contains(node)) {
                                    onPatternChange(pattern + node)
                                }
                            }
                        }
                    )
                }
        ) {
            if (pattern.size > 1) {
                for (i in 0 until pattern.lastIndex) {
                    val start = centers[pattern[i]]
                    val end = centers[pattern[i + 1]]
                    drawLine(
                        color = activeColor,
                        start = start,
                        end = end,
                        strokeWidth = size.minDimension * 0.03f
                    )
                }
            }

            centers.forEachIndexed { index, center ->
                val selected = pattern.contains(index)
                drawCircle(
                    color = if (selected) activeColor else inactiveColor,
                    radius = size.minDimension * 0.085f,
                    center = center
                )
                if (selected) {
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension * 0.03f,
                        center = center
                    )
                }
            }
        }
    }
}

private fun nodeAt(position: Offset, centers: List<Offset>, threshold: Float): Int? {
    centers.forEachIndexed { index, center ->
        val dx = position.x - center.x
        val dy = position.y - center.y
        if ((dx * dx) + (dy * dy) <= threshold * threshold) {
            return index
        }
    }
    return null
}
