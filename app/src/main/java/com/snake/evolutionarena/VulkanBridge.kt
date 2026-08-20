package com.snake.evolutionarena

import android.view.Surface
import java.nio.FloatBuffer

/**
 * Vulkan is deliberately loaded separately from the game engine. A broken or missing vendor
 * Vulkan driver can no longer prevent the battle simulation and OpenGL/Canvas paths from loading.
 */
object VulkanBridge {
    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("snake_vulkan")
            true
        }.getOrDefault(false)
    }

    fun supportLevel(): Int = if (!libraryLoaded) {
        0
    } else {
        runCatching { nativeSupportLevel() }.getOrDefault(0)
    }

    fun deviceName(): String = if (!libraryLoaded) {
        ""
    } else {
        runCatching { nativeDeviceName() }.getOrDefault("")
    }

    fun createRenderer(surface: Surface, width: Int, height: Int): Long =
        if (!libraryLoaded) 0L else runCatching {
            nativeCreateRenderer(surface, width, height)
        }.getOrDefault(0L)

    fun resizeRenderer(handle: Long, width: Int, height: Int) {
        if (libraryLoaded && handle != 0L) runCatching { nativeResizeRenderer(handle, width, height) }
    }

    fun drawableWidth(handle: Long): Int = if (!libraryLoaded || handle == 0L) {
        0
    } else {
        runCatching { nativeDrawableWidth(handle) }.getOrDefault(0)
    }

    fun drawableHeight(handle: Long): Int = if (!libraryLoaded || handle == 0L) {
        0
    } else {
        runCatching { nativeDrawableHeight(handle) }.getOrDefault(0)
    }

    fun render(
        handle: Long,
        vertices: FloatBuffer,
        vertexCount: Int,
        clearRed: Float,
        clearGreen: Float,
        clearBlue: Float,
    ): Boolean = libraryLoaded && runCatching {
        nativeRender(handle, vertices, vertexCount, clearRed, clearGreen, clearBlue)
    }.getOrDefault(false)

    fun destroyRenderer(handle: Long) {
        if (libraryLoaded && handle != 0L) runCatching { nativeDestroyRenderer(handle) }
    }

    private external fun nativeSupportLevel(): Int
    private external fun nativeDeviceName(): String
    private external fun nativeCreateRenderer(surface: Surface, width: Int, height: Int): Long
    private external fun nativeResizeRenderer(handle: Long, width: Int, height: Int)
    private external fun nativeDrawableWidth(handle: Long): Int
    private external fun nativeDrawableHeight(handle: Long): Int
    private external fun nativeRender(
        handle: Long,
        vertices: FloatBuffer,
        vertexCount: Int,
        clearRed: Float,
        clearGreen: Float,
        clearBlue: Float,
    ): Boolean
    private external fun nativeDestroyRenderer(handle: Long)
}
