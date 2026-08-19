package com.snake.evolutionarena

object NativeBridge {
    init {
        System.loadLibrary("snake_engine")
    }

    external fun coreVersion(): String
    external fun saveFormatVersion(): Int
    external fun createWorld(width: Float, height: Float, seed: Long): Long
    external fun destroyWorld(handle: Long)
    external fun advanceWorld(
        handle: Long,
        frameTimeNanos: Long,
        directionX: Float,
        directionY: Float,
        boost: Boolean,
    )
    external fun writeWorldSnapshot(handle: Long, output: FloatArray): Int
}
