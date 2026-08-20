package com.snake.evolutionarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinGameWorldRuntimeTest {
    @Test
    fun fallbackWorldProducesPlayableSnapshot() {
        val world = KotlinGameWorldRuntime(1920f, 1080f, 7L, 0, 0, 1, 0)
        val snapshot = FloatArray(8192)
        var frameTime = 1_000_000_000L
        repeat(240) {
            frameTime += 16_666_667L
            world.advance(frameTime, 1f, .15f, it % 30 < 8)
        }

        val written = world.writeSnapshot(snapshot)
        assertTrue(written > BattleSnapshot.HEADER_SIZE)
        assertTrue(snapshot[4] >= 20f)
        assertTrue(snapshot[5] >= 180f)
        assertEquals(7f, snapshot[6], .001f)
        assertTrue(snapshot[7] > 3f)
        assertEquals(1920f, snapshot[BattleSnapshot.WORLD_WIDTH], .001f)
        assertEquals(1080f, snapshot[BattleSnapshot.WORLD_HEIGHT], .001f)
        world.release()
    }

    @Test
    fun configuredSpeedMovesAcrossTheViewportAtPlayableRate() {
        val world = KotlinGameWorldRuntime(
            3200f,
            1800f,
            17L,
            0,
            0,
            1,
            0,
            baseSpeed = 360f,
            pickupRadius = 60f,
            segmentSpacing = 28f,
        )
        val snapshot = FloatArray(8192)
        world.writeSnapshot(snapshot)
        val initialX = snapshot[0]
        var frameTime = 1_000_000_000L
        world.advance(frameTime, 1f, 0f, false)
        repeat(60) {
            frameTime += 16_666_667L
            world.advance(frameTime, 1f, 0f, false)
        }
        world.writeSnapshot(snapshot)

        assertTrue("one second should move at least 320 px", snapshot[0] - initialX >= 320f)
        world.release()
    }

    @Test
    fun densityScaledPickupCollectsVisiblyOverlappingFood() {
        val world = KotlinGameWorldRuntime(
            1600f,
            900f,
            23L,
            0,
            0,
            1,
            0,
            baseSpeed = 340f,
            pickupRadius = 64f,
            segmentSpacing = 28f,
        )
        val snapshot = FloatArray(8192)
        var frameTime = 1_000_000_000L
        world.advance(frameTime, 1f, 0f, false)
        repeat(600) {
            world.writeSnapshot(snapshot)
            if (snapshot[3] > 0f) return@repeat
            val segmentCount = snapshot[4].toInt()
            val foodCount = snapshot[5].toInt()
            val foodOffset = BattleSnapshot.HEADER_SIZE + segmentCount * 2
            var closestX = snapshot[foodOffset]
            var closestY = snapshot[foodOffset + 1]
            var closestDistanceSquared = Float.MAX_VALUE
            for (foodIndex in 0 until foodCount) {
                val cursor = foodOffset + foodIndex * BattleSnapshot.FOOD_STRIDE
                val dx = snapshot[cursor] - snapshot[0]
                val dy = snapshot[cursor + 1] - snapshot[1]
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared < closestDistanceSquared) {
                    closestDistanceSquared = distanceSquared
                    closestX = snapshot[cursor]
                    closestY = snapshot[cursor + 1]
                }
            }
            frameTime += 16_666_667L
            world.advance(frameTime, closestX - snapshot[0], closestY - snapshot[1], false)
        }
        world.writeSnapshot(snapshot)

        assertTrue("steering through visible food should increase score", snapshot[3] > 0f)
        world.release()
    }
}
