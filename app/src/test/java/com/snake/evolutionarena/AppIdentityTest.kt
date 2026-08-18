package com.snake.evolutionarena

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun nativeApplicationIdentityIsStable() {
        assertEquals("蛇域进化", AppIdentity.displayName)
        assertEquals("com.snake.evolutionarena", AppIdentity.packageName)
        assertEquals("arm64-v8a", AppIdentity.supportedAbi)
    }
}
