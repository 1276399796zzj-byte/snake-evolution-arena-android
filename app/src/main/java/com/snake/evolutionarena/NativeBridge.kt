package com.snake.evolutionarena

import android.view.Surface
import java.nio.FloatBuffer

object NativeBridge {
    init {
        System.loadLibrary("snake_engine")
    }

    external fun coreVersion(): String
    external fun saveFormatVersion(): Int
    external fun createWorld(
        width: Float,
        height: Float,
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

    external fun vulkanSupportLevel(): Int
    external fun vulkanDeviceName(): String
    external fun createVulkanRenderer(surface: Surface, width: Int, height: Int): Long
    external fun resizeVulkanRenderer(handle: Long, width: Int, height: Int)
    external fun renderVulkan(
        handle: Long,
        vertices: FloatBuffer,
        vertexCount: Int,
        clearRed: Float,
        clearGreen: Float,
        clearBlue: Float,
    ): Boolean
    external fun destroyVulkanRenderer(handle: Long)
}
