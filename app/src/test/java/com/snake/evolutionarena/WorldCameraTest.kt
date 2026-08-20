package com.snake.evolutionarena

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldCameraTest {
    @Test
    fun centersPlayerInsideExpandedWorld() {
        val camera = WorldCamera()
        camera.update(1420.8f, 638.25f, 1536f, 690f, 2841.6f, 1276.5f, follow = 1f)

        assertEquals(652.8f, camera.x, .01f)
        assertEquals(293.25f, camera.y, .01f)
        assertEquals(768f, 1420.8f - camera.x, .01f)
        assertEquals(345f, 638.25f - camera.y, .01f)
    }

    @Test
    fun keepsPlayerCenteredAtWorldEdges() {
        val camera = WorldCamera()
        camera.update(40f, 30f, 1000f, 500f, 1800f, 900f, follow = 1f)
        assertEquals(-460f, camera.x, .01f)
        assertEquals(-220f, camera.y, .01f)

        camera.update(1790f, 890f, 1000f, 500f, 1800f, 900f, follow = 1f)
        assertEquals(1290f, camera.x, .01f)
        assertEquals(640f, camera.y, .01f)
    }
}
