package com.snake.evolutionarena

object NativeBridge {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("snake_engine")
        true
    }.getOrDefault(false)

    external fun coreVersion(): String
    external fun saveFormatVersion(): Int
    external fun createWorld(
        width: Float,
        height: Float,
        baseSpeed: Float,
        pickupRadius: Float,
        segmentSpacing: Float,
        seed: Long,
        mapIndex: Int,
        modeIndex: Int,
        aiLevel: Int,
        archetypeIndex: Int,
    ): Long
    external fun destroyWorld(handle: Long)
    external fun advanceWorld(
        handle: Long,
        frameTimeNanos: Long,
        directionX: Float,
        directionY: Float,
        boost: Boolean,
    )
    external fun writeWorldSnapshot(handle: Long, output: FloatArray): Int
    external fun chooseUpgrade(handle: Long, choice: Int)
    external fun activateAbility(handle: Long, ability: Int)
}
