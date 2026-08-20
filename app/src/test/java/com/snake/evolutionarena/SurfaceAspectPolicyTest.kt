package com.snake.evolutionarena

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceAspectPolicyTest {
    @Test
    fun acceptsSameLandscapeAspectAtDifferentResolution() {
        assertTrue(SurfaceAspectPolicy.matches(1536, 690, 1920, 863))
    }

    @Test
    fun rejectsRotatedOrFlattenedSwapchain() {
        assertFalse(SurfaceAspectPolicy.matches(1536, 690, 690, 1536))
        assertFalse(SurfaceAspectPolicy.matches(1536, 690, 1536, 900))
    }
}
