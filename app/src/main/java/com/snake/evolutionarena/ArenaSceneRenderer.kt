package com.snake.evolutionarena

import android.view.View

/**
 * Rendering boundary between the deterministic game/HUD view and a GPU scene surface.
 * Implementations own their render thread and must copy submitted data before returning.
 */
interface ArenaSceneRenderer {
    val view: View
    val backendLabel: String

    fun submitFrame(
        snapshot: FloatArray,
        snapshotSize: Int,
        directionX: Float,
        directionY: Float,
        pulseStartedMs: Long,
        shieldStartedMs: Long,
        performanceTier: Int,
        cameraX: Float,
        cameraY: Float,
    )

    fun onHostResume()
    fun onHostPause()
    fun release()
}
