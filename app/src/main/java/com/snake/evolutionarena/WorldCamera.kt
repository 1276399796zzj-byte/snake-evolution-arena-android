package com.snake.evolutionarena

/**
 * Converts the simulation's world coordinates into the same player-centred viewport used by the
 * web edition. Keeping the player at the centre also makes speed legible on very wide displays.
 */
internal class WorldCamera {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    private var initialized = false

    fun reset() {
        x = 0f
        y = 0f
        initialized = false
    }

    fun update(
        headX: Float,
        headY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        worldWidth: Float,
        worldHeight: Float,
        follow: Float = DEFAULT_FOLLOW,
    ) {
        if (viewportWidth <= 0f || viewportHeight <= 0f || worldWidth <= 0f || worldHeight <= 0f) return
        val targetX = headX - viewportWidth * .5f
        val targetY = headY - viewportHeight * .5f
        if (!initialized) {
            x = targetX
            y = targetY
            initialized = true
            return
        }

        val amount = follow.coerceIn(0f, 1f)
        x += (targetX - x) * amount
        y += (targetY - y) * amount
    }

    companion object {
        private const val DEFAULT_FOLLOW = 1f
    }
}
