package com.snake.evolutionarena

import kotlin.math.abs

internal object SurfaceAspectPolicy {
    fun matches(
        logicalWidth: Int,
        logicalHeight: Int,
        drawableWidth: Int,
        drawableHeight: Int,
        maximumRelativeError: Float = .04f,
    ): Boolean {
        if (logicalWidth <= 0 || logicalHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) {
            return false
        }
        if ((logicalWidth >= logicalHeight) != (drawableWidth >= drawableHeight)) return false
        val logicalAspect = logicalWidth.toFloat() / logicalHeight
        val drawableAspect = drawableWidth.toFloat() / drawableHeight
        return abs(logicalAspect - drawableAspect) / logicalAspect <= maximumRelativeError
    }
}
