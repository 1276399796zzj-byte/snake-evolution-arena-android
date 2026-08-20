package com.snake.evolutionarena

/** Allocation-free wire layout shared by the simulation and all three render paths. */
internal object BattleSnapshot {
    const val HEADER_SIZE = 25
    const val FOOD_STRIDE = 6
    const val BOT_HEADER_SIZE = 5
    const val ENEMY_STRIDE = 10
    const val PROJECTILE_STRIDE = 5
    const val PARTICLE_STRIDE = 6

    const val ENEMY_COUNT = 14
    const val PROJECTILE_COUNT = 15
    const val PARTICLE_COUNT = 16
    const val ARMOR = 17
    const val MAX_ARMOR = 18
    const val KILLS = 19
    const val COMBO = 20
    const val SHIELD_ACTIVE = 21
    const val INVULNERABLE = 22
    const val WORLD_WIDTH = 23
    const val WORLD_HEIGHT = 24
}
