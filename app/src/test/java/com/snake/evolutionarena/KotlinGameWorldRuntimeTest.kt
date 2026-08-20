package com.snake.evolutionarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinGameWorldRuntimeTest {
    @Test
    fun fallbackWorldProducesPlayableSnapshot() {
        val world = KotlinGameWorldRuntime(1920f, 1080f, 7L, 0, 1, 0)
        val snapshot = FloatArray(8192)
        var frameTime = 1_000_000_000L
        repeat(240) {
            frameTime += 16_666_667L
            world.advance(frameTime, 1f, .15f, it % 30 < 8)
        }

        val written = world.writeSnapshot(snapshot)
        assertTrue(written > 14)
        assertTrue(snapshot[4] >= 18f)
        assertEquals(72f, snapshot[5])
        assertEquals(5f, snapshot[6])
        assertTrue(snapshot[7] > 3f)
        world.release()
    }
}
