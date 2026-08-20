package com.snake.evolutionarena

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Keeps the battle playable even when a device refuses to load the native engine library. */
internal interface GameWorldRuntime {
    fun advance(frameTimeNanos: Long, directionX: Float, directionY: Float, boost: Boolean)
    fun writeSnapshot(output: FloatArray): Int
    fun chooseUpgrade(choice: Int)
    fun activateAbility(ability: Int)
    fun release()

    companion object {
        fun create(
            width: Float,
            height: Float,
            seed: Long,
            mapIndex: Int,
            modeIndex: Int,
            aiLevel: Int,
            archetypeIndex: Int,
            allowNative: Boolean = true,
        ): GameWorldRuntime {
            if (allowNative && NativeBridge.isAvailable) {
                val handle = runCatching {
                    NativeBridge.createWorld(
                        width,
                        height,
                        seed,
                        mapIndex,
                        modeIndex,
                        aiLevel,
                        archetypeIndex,
                    )
                }.getOrDefault(0L)
                if (handle != 0L) return NativeGameWorldRuntime(handle)
            }
            return KotlinGameWorldRuntime(width, height, seed, modeIndex, aiLevel, archetypeIndex)
        }
    }
}

private class NativeGameWorldRuntime(private var handle: Long) : GameWorldRuntime {
    override fun advance(frameTimeNanos: Long, directionX: Float, directionY: Float, boost: Boolean) {
        if (handle != 0L) NativeBridge.advanceWorld(handle, frameTimeNanos, directionX, directionY, boost)
    }

    override fun writeSnapshot(output: FloatArray): Int =
        if (handle == 0L) 0 else NativeBridge.writeWorldSnapshot(handle, output)

    override fun chooseUpgrade(choice: Int) {
        if (handle != 0L) NativeBridge.chooseUpgrade(handle, choice)
    }

    override fun activateAbility(ability: Int) {
        if (handle != 0L) NativeBridge.activateAbility(handle, ability)
    }

    override fun release() {
        if (handle == 0L) return
        NativeBridge.destroyWorld(handle)
        handle = 0L
    }
}

/**
 * Compact allocation-free fallback simulation. It is intentionally simpler than the C++ world,
 * but preserves steering, boost, pickups, growth, AI opponents, upgrades and match timing.
 */
internal class KotlinGameWorldRuntime(
    width: Float,
    height: Float,
    seed: Long,
    private val modeIndex: Int,
    aiLevel: Int,
    archetypeIndex: Int,
) : GameWorldRuntime {
    private data class Point(var x: Float, var y: Float)
    private data class Food(var x: Float, var y: Float, var value: Int)
    private data class Bot(
        var x: Float,
        var y: Float,
        var phase: Float,
        var score: Int,
        val segments: MutableList<Point>,
    )

    private val arenaWidth = max(320f, width)
    private val arenaHeight = max(180f, height)
    private val random = Random(seed.toInt())
    private val foods = MutableList(if (modeIndex == 1) 88 else 72) { Food(0f, 0f, 1) }
    private val playerSegments = MutableList(if (archetypeIndex == 1) 26 else 18) {
        Point(arenaWidth * .5f - it * 13f, arenaHeight * .5f)
    }
    private val bots = MutableList(if (aiLevel == 2) 6 else 5) { index ->
        val phase = index * 1.0472f
        val x = arenaWidth * .5f + cos(phase) * arenaHeight * .28f
        val y = arenaHeight * .5f + sin(phase) * arenaHeight * .28f
        Bot(x, y, phase, 0, MutableList(14 + index % 4) { Point(x, y) })
    }

    private var headX = arenaWidth * .5f
    private var headY = arenaHeight * .5f
    private var directionX = 1f
    private var directionY = 0f
    private var boostEnergy = 100f
    private var score = 0
    private var elapsedSeconds = 0f
    private var lastFrameNanos = 0L
    private var level = 1
    private var experience = 0
    private var experienceRequired = 40
    private var upgradePending = false
    private var upgradeSet = 0
    private var matchStatus = 0
    private var speedMultiplier = if (archetypeIndex == 0) 1.10f else 1f
    private var pickupRadius = if (archetypeIndex == 3) 29f else 23f

    init {
        foods.forEach(::respawnFood)
    }

    override fun advance(frameTimeNanos: Long, directionX: Float, directionY: Float, boost: Boolean) {
        if (lastFrameNanos == 0L || frameTimeNanos <= lastFrameNanos) {
            lastFrameNanos = frameTimeNanos
            return
        }
        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, .05f)
        lastFrameNanos = frameTimeNanos
        if (matchStatus != 0 || upgradePending) return
        elapsedSeconds += dt

        val desiredLength = hypot(directionX, directionY)
        if (desiredLength > .05f) {
            val targetX = directionX / desiredLength
            val targetY = directionY / desiredLength
            val response = (dt * 11f).coerceIn(0f, 1f)
            this.directionX += (targetX - this.directionX) * response
            this.directionY += (targetY - this.directionY) * response
            val normalized = hypot(this.directionX, this.directionY).coerceAtLeast(.001f)
            this.directionX /= normalized
            this.directionY /= normalized
        }

        val boosting = boost && boostEnergy > 1f
        val speed = 128f * speedMultiplier * if (boosting) 1.62f else 1f
        boostEnergy = if (boosting) {
            (boostEnergy - 30f * dt).coerceAtLeast(0f)
        } else {
            (boostEnergy + 17f * dt).coerceAtMost(100f)
        }
        headX = wrap(headX + this.directionX * speed * dt, arenaWidth)
        headY = wrap(headY + this.directionY * speed * dt, arenaHeight)
        follow(playerSegments, headX, headY, 13f)
        collectFood()
        updateBots(dt)

        val duration = when (modeIndex) { 0 -> 180f; 1 -> 600f; else -> Float.MAX_VALUE }
        if (elapsedSeconds >= duration) matchStatus = 1
    }

    override fun writeSnapshot(output: FloatArray): Int {
        val required = HEADER_SIZE + playerSegments.size * 2 + foods.size * 3 +
            bots.sumOf { 5 + it.segments.size * 2 }
        if (output.size < required) return 0
        output[0] = headX
        output[1] = headY
        output[2] = boostEnergy
        output[3] = score.toFloat()
        output[4] = playerSegments.size.toFloat()
        output[5] = foods.size.toFloat()
        output[6] = bots.size.toFloat()
        output[7] = elapsedSeconds
        output[8] = matchStatus.toFloat()
        output[9] = level.toFloat()
        output[10] = experience.toFloat()
        output[11] = experienceRequired.toFloat()
        output[12] = if (upgradePending) 1f else 0f
        output[13] = upgradeSet.toFloat()
        var cursor = HEADER_SIZE
        playerSegments.forEach { point ->
            output[cursor++] = point.x
            output[cursor++] = point.y
        }
        foods.forEach { food ->
            output[cursor++] = food.x
            output[cursor++] = food.y
            output[cursor++] = food.value.toFloat()
        }
        bots.forEach { bot ->
            output[cursor++] = bot.x
            output[cursor++] = bot.y
            output[cursor++] = bot.score.toFloat()
            output[cursor++] = bot.segments.size.toFloat()
            output[cursor++] = 1f
            bot.segments.forEach { point ->
                output[cursor++] = point.x
                output[cursor++] = point.y
            }
        }
        return cursor
    }

    override fun chooseUpgrade(choice: Int) {
        if (!upgradePending) return
        when (choice.coerceIn(0, 2)) {
            0 -> speedMultiplier *= 1.08f
            1 -> pickupRadius *= 1.16f
            else -> boostEnergy = 100f
        }
        upgradePending = false
    }

    override fun activateAbility(ability: Int) {
        when (ability) {
            0 -> {
                var absorbed = 0
                foods.forEach { food ->
                    if (absorbed >= 5) return@forEach
                    val dx = food.x - headX
                    val dy = food.y - headY
                    if (dx * dx + dy * dy <= 230f * 230f) {
                        collect(food)
                        absorbed += 1
                    }
                }
            }
            1 -> boostEnergy = max(boostEnergy, 38f)
            2 -> score += 1
        }
    }

    override fun release() = Unit

    private fun collectFood() {
        val radiusSquared = pickupRadius * pickupRadius
        foods.forEach { food ->
            val dx = food.x - headX
            val dy = food.y - headY
            if (dx * dx + dy * dy <= radiusSquared) collect(food)
        }
    }

    private fun collect(food: Food) {
        score += food.value
        experience += food.value
        repeat(food.value.coerceAtMost(2)) {
            val tail = playerSegments.last()
            playerSegments += Point(tail.x, tail.y)
        }
        respawnFood(food)
        if (!upgradePending && experience >= experienceRequired) {
            experience -= experienceRequired
            level += 1
            upgradeSet = (level - 2) % 4
            experienceRequired = (experienceRequired * 1.42f + 8f).toInt().coerceAtMost(420)
            upgradePending = true
        }
    }

    private fun updateBots(dt: Float) {
        bots.forEachIndexed { index, bot ->
            bot.phase += dt * (.42f + index * .025f)
            val orbitX = arenaWidth * (.29f + index % 2 * .035f)
            val orbitY = arenaHeight * (.27f + index % 3 * .025f)
            bot.x = arenaWidth * .5f + cos(bot.phase) * orbitX
            bot.y = arenaHeight * .5f + sin(bot.phase * 1.13f) * orbitY
            follow(bot.segments, bot.x, bot.y, 12f)
        }
    }

    private fun follow(segments: MutableList<Point>, targetX: Float, targetY: Float, spacing: Float) {
        var previousX = targetX
        var previousY = targetY
        segments.forEach { point ->
            val dx = previousX - point.x
            val dy = previousY - point.y
            val distance = hypot(dx, dy).coerceAtLeast(.001f)
            if (distance > spacing) {
                val movement = distance - spacing
                point.x += dx / distance * movement
                point.y += dy / distance * movement
            }
            previousX = point.x
            previousY = point.y
        }
    }

    private fun respawnFood(food: Food) {
        food.x = 34f + random.nextFloat() * (arenaWidth - 68f).coerceAtLeast(1f)
        food.y = 34f + random.nextFloat() * (arenaHeight - 68f).coerceAtLeast(1f)
        food.value = 1 + random.nextInt(3)
    }

    private fun wrap(value: Float, boundary: Float): Float = when {
        value < 12f -> boundary - 12f
        value > boundary - 12f -> 12f
        else -> value
    }

    private companion object {
        const val HEADER_SIZE = 14
    }
}
