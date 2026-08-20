package com.snake.evolutionarena

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Simulation contract shared by Canvas, OpenGL ES and Vulkan renderers. */
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
            baseSpeed: Float,
            pickupRadius: Float,
            segmentSpacing: Float,
            seed: Long,
            mapIndex: Int,
            modeIndex: Int,
            aiLevel: Int,
            archetypeIndex: Int,
            @Suppress("UNUSED_PARAMETER") allowNative: Boolean = true,
        ): GameWorldRuntime = KotlinGameWorldRuntime(
            width = width,
            height = height,
            seed = seed,
            mapIndex = mapIndex,
            modeIndex = modeIndex,
            aiLevel = aiLevel,
            archetypeIndex = archetypeIndex,
            baseSpeed = baseSpeed,
            pickupRadius = pickupRadius,
            segmentSpacing = segmentSpacing,
        )
    }
}

/**
 * Android port of the v12 web battle engine. Rendering APIs now differ only in presentation:
 * gameplay, camera coordinates, collisions, AI and effects all consume this one snapshot.
 */
internal class KotlinGameWorldRuntime(
    width: Float,
    height: Float,
    seed: Long,
    private val mapIndex: Int = 0,
    private val modeIndex: Int,
    private val aiLevel: Int,
    private val archetypeIndex: Int,
    private val baseSpeed: Float = 164f,
    private var pickupRadius: Float = 32f,
    private val segmentSpacing: Float = 5.6f,
) : GameWorldRuntime {
    private data class Point(var x: Float, var y: Float)
    private data class Food(
        var x: Float,
        var y: Float,
        var value: Int,
        var hue: Float,
        var pulse: Float,
        var special: Int,
    )

    private data class Bot(
        var x: Float,
        var y: Float,
        var angle: Float,
        var targetAngle: Float,
        var speed: Float,
        var targetLength: Float,
        var score: Int,
        var profile: Int,
        var retarget: Float,
        var targetX: Float,
        var targetY: Float,
        var invulnerable: Float,
        var alive: Boolean,
        val segments: MutableList<Point>,
    )

    private data class Enemy(
        var x: Float,
        var y: Float,
        var kind: Int,
        var hp: Float,
        var maxHp: Float,
        var radius: Float,
        var speed: Float,
        var angle: Float,
        var phase: Float,
        var elite: Boolean,
        var flash: Float = 0f,
        var attackCooldown: Float = 0f,
    )

    private data class Projectile(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var damage: Float,
        var radius: Float,
        var life: Float,
        var colorSlot: Int,
    )

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var life: Float,
        var maxLife: Float,
        var colorSlot: Int,
    )

    private val arenaWidth = max(960f, width)
    private val arenaHeight = max(580f, height)
    private val worldScale = arenaWidth / WEB_WORLD_WIDTH
    private val random = Random(seed.toInt())
    private val foods = mutableListOf<Food>()
    private val bots = mutableListOf<Bot>()
    private val enemies = mutableListOf<Enemy>()
    private val projectiles = mutableListOf<Projectile>()
    private val particles = mutableListOf<Particle>()
    private val playerSegments = mutableListOf<Point>()

    private var headX = arenaWidth * .5f
    private var headY = arenaHeight * .5f
    private var angle = 0f
    private var targetAngle = 0f
    private var inputX = 1f
    private var inputY = 0f
    private var boostRequested = false
    private var energy = 100f
    private var armor = 2f
    private var maxArmor = 2f
    private var targetLength = if (archetypeIndex == 1) 54f else 46f
    private var score = 0f
    private var elapsedSeconds = 0f
    private var level = 1
    private var experience = 0f
    private var experienceRequired = 105f
    private var kills = 0
    private var combo = 0
    private var comboTimer = 0f
    private var scoreMultiplier = 1f
    private var upgradePending = false
    private var upgradeSet = 0
    private var nextUpgradeAllowedAt = 12f
    private var matchStatus = 0
    private var alive = true
    private var respawnTimer = 0f
    private var invulnerable = 2.2f
    private var shieldTimer = 0f
    private var dashTimer = 0f
    private var pulseCooldown = 0f
    private var dashCooldown = 0f
    private var shieldCooldown = 0f
    private var fireCooldown = 0f
    private var enemySpawnCooldown = 1.5f
    private var aiSpawnCooldown = 8f
    private var hazardCooldown = 0f
    private var portalCooldown = 0f
    private var mapBuffTimer = 0f
    private var bossSpawned = false
    private var bossDefeated = false
    private var speedRank = 0
    private var pickupRank = 0
    private var damageRank = 0
    private var fireRateRank = 0
    private var growthRank = 0
    private var scoreRank = 0
    private var lastFrameNanos = 0L
    private var accumulator = 0f

    init {
        repeat(targetLength.toInt()) { index ->
            playerSegments += Point(headX - index * segmentSpacing, headY)
        }
        val foodCount = if (aiLevel == 0) 210 else 180
        repeat(foodCount) { foods += createFood() }
        val botCount = when (aiLevel) { 0 -> 5; 2 -> 9; else -> 7 }
        repeat(botCount) { bots += createBot(it) }
        repeat(5) { enemies += createEnemy(if (it < 3) ENEMY_MITE else ENEMY_DRONE) }
    }

    override fun advance(frameTimeNanos: Long, directionX: Float, directionY: Float, boost: Boolean) {
        val magnitude = hypot(directionX, directionY)
        if (magnitude > .08f) {
            inputX = directionX / magnitude
            inputY = directionY / magnitude
        }
        boostRequested = boost
        if (lastFrameNanos == 0L || frameTimeNanos <= lastFrameNanos) {
            lastFrameNanos = frameTimeNanos
            return
        }
        val frameSeconds = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, .1f)
        lastFrameNanos = frameTimeNanos
        if (matchStatus != 0 || upgradePending) {
            accumulator = 0f
            return
        }
        accumulator = min(accumulator + frameSeconds, FIXED_STEP * 5f)
        while (accumulator >= FIXED_STEP) {
            update(FIXED_STEP)
            accumulator -= FIXED_STEP
        }
    }

    private fun update(dt: Float) {
        elapsedSeconds += dt
        comboTimer -= dt
        portalCooldown -= dt
        hazardCooldown -= dt
        mapBuffTimer -= dt
        pulseCooldown = max(0f, pulseCooldown - dt)
        dashCooldown = max(0f, dashCooldown - dt)
        shieldCooldown = max(0f, shieldCooldown - dt)
        shieldTimer = max(0f, shieldTimer - dt)
        dashTimer = max(0f, dashTimer - dt)
        invulnerable = max(0f, invulnerable - dt)
        if (comboTimer <= 0f) {
            combo = 0
            scoreMultiplier = 1f
        }

        val duration = when (modeIndex) { 0 -> 180f; 1 -> 600f; else -> Float.MAX_VALUE }
        if (elapsedSeconds >= duration) {
            matchStatus = if (modeIndex == 1 && !bossDefeated) -1 else 1
            return
        }

        if (!alive) {
            respawnTimer -= dt
            if (modeIndex == 0 && respawnTimer <= 0f) respawnPlayer()
            updateParticles(dt)
            return
        }

        updatePlayer(dt)
        updateBots(dt)
        updateEnemies(dt)
        updateProjectiles(dt)
        updateFoods(dt)
        updateMapMechanics(dt)
        updatePlayerCollisions()
        updateSpawns(dt)
        updateParticles(dt)
        checkLevelUp()
    }

    private fun updatePlayer(dt: Float) {
        targetAngle = atan2(inputY, inputX)
        val steer = if (dashTimer > 0f) 4.5f else 8.5f
        angle += angleDelta(angle, targetAngle) * min(1f, dt * steer)
        val canBoost = boostRequested && energy > 2f
        val shieldActive = shieldTimer > 0f && energy > 1f
        if (canBoost) energy = max(0f, energy - dt * 26f)
        if (shieldActive) energy = max(0f, energy - dt * 18f)
        if (!canBoost && !shieldActive) energy = min(100f, energy + dt * 15f)
        val archetypeSpeed = when (archetypeIndex) { 0 -> 1.1f; 1 -> .95f; else -> 1f }
        val rankSpeed = 1f + speedRank * .07f
        val mapBuff = if (mapBuffTimer > 0f) 1.16f else 1f
        val laneBoost = if (mapIndex == 0 && abs(headY - arenaHeight * .5f) < scaled(76f)) 1.09f else 1f
        val boostMultiplier = if (dashTimer > 0f) 2.55f else if (canBoost) 1.58f else 1f
        val actualSpeed = baseSpeed * archetypeSpeed * rankSpeed * mapBuff * laneBoost * boostMultiplier
        headX += cos(angle) * actualSpeed * dt
        headY += sin(angle) * actualSpeed * dt
        val margin = scaled(26f)
        val hitBoundary = headX < margin || headX > arenaWidth - margin || headY < margin || headY > arenaHeight - margin
        headX = headX.coerceIn(margin, arenaWidth - margin)
        headY = headY.coerceIn(margin, arenaHeight - margin)
        if (hitBoundary && invulnerable <= 0f) {
            damagePlayer()
            targetAngle += (PI * .72).toFloat()
        }
        advanceBody(playerSegments, headX, headY, floor(targetLength).toInt().coerceAtLeast(20))
        fireCooldown -= dt
        if (fireCooldown <= 0f) autoFire()
    }

    private fun updateBots(dt: Float) {
        val awareness = scaled(when (aiLevel) { 2 -> 620f; 1 -> 500f; else -> 390f })
        bots.forEach { bot ->
            if (!bot.alive) return@forEach
            bot.invulnerable = max(0f, bot.invulnerable - dt)
            bot.retarget -= dt
            val toPlayer = distance(bot.x, bot.y, headX, headY)
            if (bot.retarget <= 0f) {
                bot.retarget = randomRange(.35f, 1.05f)
                val aggressive = bot.profile == 1 || bot.profile == 2 || bot.profile == 5
                if (aggressive && alive && toPlayer < awareness) {
                    val lead = scaled(if (bot.profile == 2) 190f else 70f)
                    bot.targetX = headX + cos(angle) * lead
                    bot.targetY = headY + sin(angle) * lead
                } else {
                    var target: Food? = null
                    var best = Float.MAX_VALUE
                    var index = 0
                    while (index < foods.size) {
                        val food = foods[index]
                        val distance = distanceSquared(bot.x, bot.y, food.x, food.y) / (food.value * food.value)
                        if (distance < best) {
                            best = distance
                            target = food
                        }
                        index += 3
                    }
                    bot.targetX = target?.x ?: randomRange(scaled(100f), arenaWidth - scaled(100f))
                    bot.targetY = target?.y ?: randomRange(scaled(100f), arenaHeight - scaled(100f))
                }
            }
            bot.targetAngle = atan2(bot.targetY - bot.y, bot.targetX - bot.x)
            val edge = scaled(130f)
            if (bot.x < edge) bot.targetAngle = 0f
            if (bot.x > arenaWidth - edge) bot.targetAngle = PI.toFloat()
            if (bot.y < edge) bot.targetAngle = (PI / 2).toFloat()
            if (bot.y > arenaHeight - edge) bot.targetAngle = (-PI / 2).toFloat()
            if (aiLevel != 0 && toPlayer < scaled(135f) && bot.targetLength < targetLength) {
                bot.targetAngle += (PI * .72).toFloat()
            }
            val steering = if (bot.profile == 5) 7.5f else 5.2f
            bot.angle += angleDelta(bot.angle, bot.targetAngle) * min(1f, dt * steering)
            val boosting = (bot.profile == 5 && toPlayer < scaled(320f)) ||
                (bot.profile == 3 && distance(bot.x, bot.y, bot.targetX, bot.targetY) < scaled(130f))
            val speed = bot.speed * if (boosting) 1.28f else 1f
            bot.x = (bot.x + cos(bot.angle) * speed * dt).coerceIn(scaled(28f), arenaWidth - scaled(28f))
            bot.y = (bot.y + sin(bot.angle) * speed * dt).coerceIn(scaled(28f), arenaHeight - scaled(28f))
            advanceBody(bot.segments, bot.x, bot.y, floor(bot.targetLength).toInt().coerceAtLeast(20))

            val pickupSquared = scaled(25f) * scaled(25f)
            val food = foods.firstOrNull { distanceSquared(bot.x, bot.y, it.x, it.y) < pickupSquared }
            if (food != null) {
                bot.targetLength = min(170f, bot.targetLength + food.value * .38f)
                bot.score += food.value * 8
                respawnFood(food)
            }
            if (bot.invulnerable <= 0f) checkBotHitsPlayer(bot)
        }
    }

    private fun updateEnemies(dt: Float) {
        enemies.forEach { enemy ->
            enemy.phase += dt * if (enemy.kind == ENEMY_MITE) 4f else 1.6f
            enemy.flash = max(0f, enemy.flash - dt)
            enemy.attackCooldown = max(0f, enemy.attackCooldown - dt)
            val dx = headX - enemy.x
            val dy = headY - enemy.y
            val distance = hypot(dx, dy).coerceAtLeast(.001f)
            enemy.angle = atan2(dy, dx)
            val movement = when (enemy.kind) {
                ENEMY_TURRET -> 0f
                ENEMY_DRONE -> enemy.speed * (.72f + sin(enemy.phase) * .14f)
                else -> enemy.speed
            }
            enemy.x = (enemy.x + dx / distance * movement * dt).coerceIn(enemy.radius, arenaWidth - enemy.radius)
            enemy.y = (enemy.y + dy / distance * movement * dt).coerceIn(enemy.radius, arenaHeight - enemy.radius)
            if ((enemy.kind == ENEMY_TURRET || enemy.kind == ENEMY_BOSS) && enemy.attackCooldown <= 0f && distance < scaled(620f)) {
                val bulletSpeed = scaled(if (enemy.kind == ENEMY_BOSS) 360f else 270f)
                projectiles += Projectile(
                    x = enemy.x,
                    y = enemy.y,
                    vx = dx / distance * bulletSpeed,
                    vy = dy / distance * bulletSpeed,
                    damage = -1f,
                    radius = scaled(if (enemy.kind == ENEMY_BOSS) 8f else 6f),
                    life = 2.2f,
                    colorSlot = COLOR_DANGER,
                )
                enemy.attackCooldown = if (enemy.kind == ENEMY_BOSS) .72f else 1.65f
            }
            if (distance < enemy.radius + scaled(15f) && invulnerable <= 0f) {
                if (shieldTimer > 0f || dashTimer > 0f) {
                    damageEnemy(enemy, 36f * damageMultiplier())
                    enemy.x -= cos(enemy.angle) * scaled(42f)
                    enemy.y -= sin(enemy.angle) * scaled(42f)
                } else {
                    damagePlayer()
                }
            }
        }
        enemies.removeAll { it.hp <= 0f }
    }

    private fun updateProjectiles(dt: Float) {
        projectiles.forEach { projectile ->
            projectile.x += projectile.vx * dt
            projectile.y += projectile.vy * dt
            projectile.life -= dt
            if (projectile.damage < 0f) {
                if (alive && invulnerable <= 0f && distanceSquared(projectile.x, projectile.y, headX, headY) <
                    (projectile.radius + scaled(13f)) * (projectile.radius + scaled(13f))
                ) {
                    projectile.life = 0f
                    if (shieldTimer <= 0f && dashTimer <= 0f) damagePlayer()
                }
            } else {
                val enemy = enemies.firstOrNull {
                    it.hp > 0f && distanceSquared(projectile.x, projectile.y, it.x, it.y) <
                        (it.radius + projectile.radius) * (it.radius + projectile.radius)
                }
                if (enemy != null) {
                    damageEnemy(enemy, projectile.damage)
                    projectile.life = 0f
                }
            }
        }
        projectiles.removeAll {
            it.life <= 0f || it.x <= 0f || it.x >= arenaWidth || it.y <= 0f || it.y >= arenaHeight
        }
    }

    private fun updateFoods(dt: Float) {
        val archetypePickup = when (archetypeIndex) { 2 -> 1.08f; 3 -> 1.3f; else -> 1f }
        val actualPickup = pickupRadius * archetypePickup * (1f + pickupRank * .28f)
        foods.forEach { food ->
            food.pulse += dt * 2.4f
            var distance = distance(food.x, food.y, headX, headY)
            val magnetRadius = actualPickup * 2.7f
            if (distance > 1f && distance < magnetRadius) {
                val pull = (1f - distance / magnetRadius).coerceIn(0f, 1f) * scaled(290f) * dt
                food.x += (headX - food.x) / distance * pull
                food.y += (headY - food.y) / distance * pull
                distance = distance(food.x, food.y, headX, headY)
            }
            if (distance < actualPickup) collectFood(food)
        }
    }

    private fun updateMapMechanics(dt: Float) {
        when (mapIndex) {
            0 -> {
                val leftX = scaled(270f)
                val rightX = arenaWidth - scaled(270f)
                val portalY = arenaHeight * .5f
                if (portalCooldown <= 0f && distance(headX, headY, leftX, portalY) < scaled(52f)) {
                    headX = rightX - scaled(74f)
                    headY = portalY
                    portalCooldown = 3f
                    invulnerable = max(invulnerable, .8f)
                    resetBodyAtHead()
                } else if (portalCooldown <= 0f && distance(headX, headY, rightX, portalY) < scaled(52f)) {
                    headX = leftX + scaled(74f)
                    headY = portalY
                    portalCooldown = 3f
                    invulnerable = max(invulnerable, .8f)
                    resetBodyAtHead()
                }
            }
            1 -> {
                val inThorns = WILD_THORNS.any { area ->
                    distance(headX, headY, scaled(area[0]), scaled(area[1])) < scaled(area[2])
                }
                if (inThorns && hazardCooldown <= 0f && shieldTimer <= 0f) {
                    hazardCooldown = 3.5f
                    energy = max(0f, energy - 28f)
                    spawnParticles(headX, headY, COLOR_DANGER, 12, scaled(105f))
                }
            }
            else -> {
                val inPool = LAB_POOLS.any { area ->
                    distance(headX, headY, scaled(area[0]), scaled(area[1])) < scaled(area[2])
                }
                if (inPool) {
                    energy = max(0f, energy - dt * 9f)
                    if (hazardCooldown <= 0f && energy <= 1f) {
                        hazardCooldown = 4.2f
                        damagePlayer()
                    }
                }
            }
        }
    }

    private fun updatePlayerCollisions() {
        if (!alive || invulnerable > 0f || shieldTimer > 0f || dashTimer > 0f) return
        bots.forEach { bot ->
            if (!bot.alive) return@forEach
            var index = 11
            while (index < bot.segments.size) {
                val point = bot.segments[index]
                if (distanceSquared(headX, headY, point.x, point.y) < scaled(19f) * scaled(19f)) {
                    damagePlayer()
                    return
                }
                index += 4
            }
        }
    }

    private fun updateSpawns(dt: Float) {
        enemySpawnCooldown -= dt
        aiSpawnCooldown -= dt
        val maxEnemies = when (modeIndex) { 2 -> 18; 1 -> 15; else -> 11 }
        if (enemySpawnCooldown <= 0f && enemies.size < maxEnemies) {
            val elapsedTier = floor(elapsedSeconds / 50f).toInt()
            val roll = random.nextFloat()
            val kind = when {
                elapsedTier > 3 && roll > .84f -> ENEMY_BRUTE
                elapsedTier > 1 && roll > .68f -> ENEMY_TURRET
                roll > .48f -> ENEMY_DRONE
                else -> ENEMY_MITE
            }
            val eliteChance = if (aiLevel == 2) .18f else .08f
            enemies += createEnemy(kind, random.nextFloat() < eliteChance)
            enemySpawnCooldown = max(.75f, 3.4f - elapsedSeconds / 130f)
        }
        val maxBots = if (aiLevel == 2) 9 else 7
        if (aiSpawnCooldown <= 0f && bots.count { it.alive } < maxBots) {
            bots += createBot(bots.size)
            aiSpawnCooldown = 9f
        }
        if (modeIndex == 1 && elapsedSeconds >= 480f && !bossSpawned) {
            bossSpawned = true
            enemies += createEnemy(ENEMY_BOSS, elite = true).apply {
                x = arenaWidth * .5f
                y = scaled(180f)
            }
        }
    }

    private fun updateParticles(dt: Float) {
        particles.forEach { particle ->
            particle.x += particle.vx * dt
            particle.y += particle.vy * dt
            val drag = 1f - min(1f, dt * 4f)
            particle.vx *= drag
            particle.vy *= drag
            particle.life -= dt
        }
        particles.removeAll { it.life <= 0f }
    }

    private fun autoFire() {
        val range = scaled(470f) * if (archetypeIndex == 2) 1.12f else 1f
        var target: Enemy? = null
        var best = range * range
        enemies.forEach { enemy ->
            val distance = distanceSquared(headX, headY, enemy.x, enemy.y)
            if (distance < best) {
                best = distance
                target = enemy
            }
        }
        val found = target
        if (found == null) {
            fireCooldown = .12f
            return
        }
        fireCooldown = max(.16f, .62f * (1f - fireRateRank * .14f))
        val bulletAngle = atan2(found.y - headY, found.x - headX)
        val bulletSpeed = scaled(570f)
        projectiles += Projectile(
            x = headX + cos(bulletAngle) * scaled(24f),
            y = headY + sin(bulletAngle) * scaled(24f),
            vx = cos(bulletAngle) * bulletSpeed,
            vy = sin(bulletAngle) * bulletSpeed,
            damage = 22f * damageMultiplier(),
            radius = scaled(5f),
            life = 1.2f,
            colorSlot = COLOR_PRIMARY,
        )
    }

    private fun collectFood(food: Food) {
        val growthMultiplier = 1f + growthRank * .18f
        targetLength = min(260f, targetLength + food.value * .52f * growthMultiplier)
        score += food.value * 11f * (1f + scoreRank * .12f) * scoreMultiplier
        experience = min(
            experience + food.value * 1.6f * if (archetypeIndex == 3) 1.12f else 1f,
            experienceRequired * 1.6f,
        )
        energy = min(100f, energy + food.value * 2.4f)
        when (food.special) {
            SPECIAL_HEAL -> armor = min(maxArmor, armor + 1f)
            SPECIAL_MUTAGEN -> mapBuffTimer = 12f
            SPECIAL_CHARGE -> {
                pulseCooldown = max(0f, pulseCooldown - 2.5f)
                dashCooldown = max(0f, dashCooldown - 2.5f)
            }
        }
        spawnParticles(food.x, food.y, if (food.special != 0) COLOR_WHITE else COLOR_PRIMARY, if (food.special != 0) 14 else 5, scaled(80f))
        respawnFood(food)
    }

    private fun checkBotHitsPlayer(bot: Bot) {
        if (!alive || distanceSquared(bot.x, bot.y, headX, headY) >= scaled(24f) * scaled(24f)) return
        if (bot.targetLength <= targetLength || shieldTimer > 0f || dashTimer > 0f) killBot(bot) else damagePlayer()
    }

    private fun killBot(bot: Bot) {
        if (!bot.alive) return
        bot.alive = false
        val drops = (bot.targetLength / 3.2f).toInt().coerceIn(10, 34)
        repeat(drops) { index ->
            val point = bot.segments[min(bot.segments.lastIndex, index * 3)]
            foods += createFood(
                point.x + randomRange(scaled(-15f), scaled(15f)),
                point.y + randomRange(scaled(-15f), scaled(15f)),
                if (random.nextFloat() > .7f) 3 else 2,
            )
        }
        score += 240f * scoreMultiplier
        experience = min(experience + 24f, experienceRequired * 1.6f)
        kills += 1
        addCombo()
        spawnParticles(bot.x, bot.y, COLOR_SECONDARY, 26, scaled(190f))
    }

    private fun damageEnemy(enemy: Enemy, damage: Float) {
        if (enemy.hp <= 0f) return
        enemy.hp -= damage
        enemy.flash = .12f
        spawnParticles(enemy.x, enemy.y, if (enemy.elite) COLOR_GOLD else COLOR_SECONDARY, 3, scaled(75f))
        if (enemy.hp <= 0f) killEnemy(enemy)
    }

    private fun killEnemy(enemy: Enemy) {
        if (enemy.hp < -900f) return
        enemy.hp = -1000f
        val value = when (enemy.kind) {
            ENEMY_BOSS -> 1400
            ENEMY_BRUTE -> 90
            ENEMY_TURRET -> 58
            ENEMY_DRONE -> 38
            else -> 24
        }
        val elite = if (enemy.elite) 1.8f else 1f
        score += value * elite * scoreMultiplier
        experience = min(experience + value * .2f * elite, experienceRequired * 1.6f)
        kills += 1
        addCombo()
        val drops = if (enemy.kind == ENEMY_BOSS) 38 else if (enemy.elite) 12 else 5
        repeat(drops) {
            val dropAngle = randomRange(0f, TAU)
            val radius = randomRange(scaled(12f), enemy.radius + scaled(48f))
            foods += createFood(
                enemy.x + cos(dropAngle) * radius,
                enemy.y + sin(dropAngle) * radius,
                if (enemy.kind == ENEMY_BOSS) 4 else if (enemy.elite) 3 else 2,
            )
        }
        spawnParticles(enemy.x, enemy.y, if (enemy.elite) COLOR_GOLD else COLOR_SECONDARY, if (enemy.kind == ENEMY_BOSS) 70 else 18, scaled(if (enemy.kind == ENEMY_BOSS) 320f else 150f))
        if (enemy.kind == ENEMY_BOSS) {
            bossDefeated = true
            matchStatus = 1
        }
    }

    private fun damagePlayer() {
        if (!alive || invulnerable > 0f || shieldTimer > 0f || dashTimer > 0f) return
        if (armor > 0f) {
            armor -= 1f
            invulnerable = 2.1f
            val lost = max(4, floor(targetLength * .25f).toInt())
            targetLength = max(20f, targetLength - lost)
            while (playerSegments.size > targetLength.toInt()) playerSegments.removeAt(playerSegments.lastIndex)
            spawnParticles(headX, headY, COLOR_DANGER, 24, scaled(180f))
            return
        }
        alive = false
        shieldTimer = 0f
        spawnParticles(headX, headY, COLOR_PRIMARY, 46, scaled(270f))
        if (modeIndex == 0) {
            score *= .85f
            respawnTimer = 2.6f
        } else {
            matchStatus = -1
        }
    }

    private fun respawnPlayer() {
        headX = arenaWidth * .5f + randomRange(scaled(-220f), scaled(220f))
        headY = arenaHeight * .5f + randomRange(scaled(-160f), scaled(160f))
        targetLength = max(30f, targetLength * .72f)
        armor = maxArmor
        energy = 100f
        invulnerable = 3f
        alive = true
        resetBodyAtHead()
    }

    private fun checkLevelUp() {
        if (upgradePending || elapsedSeconds < nextUpgradeAllowedAt || experience < experienceRequired) return
        experience -= experienceRequired
        level += 1
        experienceRequired = min(720f, floor(experienceRequired * 1.32f + 26f))
        upgradeSet = (level - 2) % 4
        upgradePending = true
    }

    override fun chooseUpgrade(choice: Int) {
        if (!upgradePending) return
        when (upgradeSet) {
            0 -> when (choice.coerceIn(0, 2)) { 0 -> fireRateRank += 1; 1 -> pickupRank += 1; else -> energy = 100f }
            1 -> when (choice.coerceIn(0, 2)) { 0 -> targetLength += 8f; 1 -> speedRank += 1; else -> armor = min(maxArmor, armor + 1f) }
            2 -> when (choice.coerceIn(0, 2)) { 0 -> scoreRank += 1; 1 -> growthRank += 1; else -> damageRank += 1 }
            else -> when (choice.coerceIn(0, 2)) { 0 -> damageRank += 1; 1 -> speedRank += 1; else -> pickupRank += 1 }
        }
        targetLength = min(260f, targetLength + 2.5f)
        nextUpgradeAllowedAt = elapsedSeconds + if (modeIndex == 0) 22f else 28f
        upgradePending = false
        spawnParticles(headX, headY, COLOR_WHITE, 16, scaled(120f))
    }

    override fun activateAbility(ability: Int) {
        if (!alive || upgradePending || matchStatus != 0) return
        when (ability) {
            0 -> if (pulseCooldown <= 0f) {
                pulseCooldown = 7.2f
                val radius = scaled(205f)
                enemies.forEach { enemy ->
                    if (distanceSquared(headX, headY, enemy.x, enemy.y) < radius * radius) {
                        damageEnemy(enemy, 52f * damageMultiplier())
                    }
                }
                bots.forEach { bot ->
                    if (bot.alive && distanceSquared(headX, headY, bot.x, bot.y) < radius * radius && bot.targetLength < targetLength * .55f) {
                        killBot(bot)
                    }
                }
                spawnParticles(headX, headY, COLOR_PRIMARY, 22, scaled(220f))
            }
            1 -> if (dashCooldown <= 0f) {
                dashCooldown = 8.4f
                dashTimer = .46f
                invulnerable = max(invulnerable, .66f)
            }
            2 -> if (shieldCooldown <= 0f) {
                shieldCooldown = 9.2f
                shieldTimer = if (archetypeIndex == 1) 4.2f else 3.1f
            }
        }
    }

    override fun writeSnapshot(output: FloatArray): Int {
        val required = BattleSnapshot.HEADER_SIZE + playerSegments.size * 2 +
            foods.size * BattleSnapshot.FOOD_STRIDE +
            bots.sumOf { BattleSnapshot.BOT_HEADER_SIZE + it.segments.size * 2 } +
            enemies.size * BattleSnapshot.ENEMY_STRIDE +
            projectiles.size * BattleSnapshot.PROJECTILE_STRIDE +
            particles.size * BattleSnapshot.PARTICLE_STRIDE
        if (output.size < required) return 0
        output[0] = headX
        output[1] = headY
        output[2] = energy
        output[3] = score
        output[4] = playerSegments.size.toFloat()
        output[5] = foods.size.toFloat()
        output[6] = bots.size.toFloat()
        output[7] = elapsedSeconds
        output[8] = matchStatus.toFloat()
        output[9] = level.toFloat()
        output[10] = experience
        output[11] = experienceRequired
        output[12] = if (upgradePending) 1f else 0f
        output[13] = upgradeSet.toFloat()
        output[BattleSnapshot.ENEMY_COUNT] = enemies.size.toFloat()
        output[BattleSnapshot.PROJECTILE_COUNT] = projectiles.size.toFloat()
        output[BattleSnapshot.PARTICLE_COUNT] = particles.size.toFloat()
        output[BattleSnapshot.ARMOR] = armor
        output[BattleSnapshot.MAX_ARMOR] = maxArmor
        output[BattleSnapshot.KILLS] = kills.toFloat()
        output[BattleSnapshot.COMBO] = combo.toFloat()
        output[BattleSnapshot.SHIELD_ACTIVE] = if (shieldTimer > 0f) 1f else 0f
        output[BattleSnapshot.INVULNERABLE] = if (invulnerable > 0f) 1f else 0f
        output[BattleSnapshot.WORLD_WIDTH] = arenaWidth
        output[BattleSnapshot.WORLD_HEIGHT] = arenaHeight
        var cursor = BattleSnapshot.HEADER_SIZE
        playerSegments.forEach { point ->
            output[cursor++] = point.x
            output[cursor++] = point.y
        }
        foods.forEach { food ->
            output[cursor++] = food.x
            output[cursor++] = food.y
            output[cursor++] = food.value.toFloat()
            output[cursor++] = food.hue
            output[cursor++] = food.pulse
            output[cursor++] = food.special.toFloat()
        }
        bots.forEach { bot ->
            output[cursor++] = bot.x
            output[cursor++] = bot.y
            output[cursor++] = bot.score.toFloat()
            output[cursor++] = bot.segments.size.toFloat()
            output[cursor++] = if (bot.alive) 1f else 0f
            bot.segments.forEach { point ->
                output[cursor++] = point.x
                output[cursor++] = point.y
            }
        }
        enemies.forEach { enemy ->
            output[cursor++] = enemy.x
            output[cursor++] = enemy.y
            output[cursor++] = enemy.kind.toFloat()
            output[cursor++] = max(0f, enemy.hp)
            output[cursor++] = enemy.maxHp
            output[cursor++] = enemy.radius
            output[cursor++] = enemy.angle
            output[cursor++] = enemy.phase
            output[cursor++] = if (enemy.elite) 1f else 0f
            output[cursor++] = enemy.flash
        }
        projectiles.forEach { projectile ->
            output[cursor++] = projectile.x
            output[cursor++] = projectile.y
            output[cursor++] = projectile.radius
            output[cursor++] = projectile.life
            output[cursor++] = projectile.colorSlot.toFloat()
        }
        particles.forEach { particle ->
            output[cursor++] = particle.x
            output[cursor++] = particle.y
            output[cursor++] = particle.size
            output[cursor++] = particle.life
            output[cursor++] = particle.maxLife
            output[cursor++] = particle.colorSlot.toFloat()
        }
        return cursor
    }

    override fun release() = Unit

    private fun createFood(
        x: Float = randomRange(scaled(90f), arenaWidth - scaled(90f)),
        y: Float = randomRange(scaled(90f), arenaHeight - scaled(90f)),
        forcedValue: Int? = null,
    ): Food {
        val roll = random.nextFloat()
        val special = if (roll > .988f) {
            when (mapIndex) { 1 -> SPECIAL_HEAL; 2 -> SPECIAL_MUTAGEN; else -> SPECIAL_CHARGE }
        } else {
            SPECIAL_NONE
        }
        val value = forcedValue ?: if (special != 0) 5 else if (roll > .91f) 3 else if (roll > .62f) 2 else 1
        return Food(x, y, value, randomRange(0f, 360f), randomRange(0f, TAU), special)
    }

    private fun respawnFood(food: Food) {
        val replacement = createFood()
        food.x = replacement.x
        food.y = replacement.y
        food.value = replacement.value
        food.hue = replacement.hue
        food.pulse = replacement.pulse
        food.special = replacement.special
    }

    private fun createBot(index: Int): Bot {
        val edge = random.nextInt(4)
        val margin = scaled(140f)
        val x: Float
        val y: Float
        when (edge) {
            0 -> { x = randomRange(scaled(120f), arenaWidth - scaled(120f)); y = margin }
            1 -> { x = arenaWidth - margin; y = randomRange(scaled(120f), arenaHeight - scaled(120f)) }
            2 -> { x = randomRange(scaled(120f), arenaWidth - scaled(120f)); y = arenaHeight - margin }
            else -> { x = margin; y = randomRange(scaled(120f), arenaHeight - scaled(120f)) }
        }
        val startAngle = randomRange(0f, TAU)
        val length = randomRange(34f, 76f)
        val difficultySpeed = when (aiLevel) { 2 -> 1.08f; 0 -> .9f; else -> 1f }
        val speed = randomRange(scaled(132f), scaled(162f)) * difficultySpeed
        val segments = MutableList(length.toInt()) { bodyIndex ->
            Point(
                x - cos(startAngle) * bodyIndex * segmentSpacing,
                y - sin(startAngle) * bodyIndex * segmentSpacing,
            )
        }
        return Bot(x, y, startAngle, startAngle, speed, length, (length * 7f).toInt(), index % 6, 0f, x, y, 1.4f, true, segments)
    }

    private fun createEnemy(kind: Int, elite: Boolean = false): Enemy {
        var x = randomRange(scaled(110f), arenaWidth - scaled(110f))
        var y = randomRange(scaled(110f), arenaHeight - scaled(110f))
        if (distance(x, y, headX, headY) < scaled(360f)) {
            x = (x + scaled(620f)).coerceIn(scaled(110f), arenaWidth - scaled(110f))
            y = (y + scaled(360f)).coerceIn(scaled(110f), arenaHeight - scaled(110f))
        }
        val scaling = 1f + elapsedSeconds / if (modeIndex == 2) 115f else 180f
        val difficulty = when (aiLevel) { 2 -> 1.22f; 0 -> .86f; else -> 1f }
        val baseHp: Float
        val baseRadius: Float
        val baseMovement: Float
        when (kind) {
            ENEMY_DRONE -> { baseHp = 72f; baseRadius = 18f; baseMovement = 52f }
            ENEMY_TURRET -> { baseHp = 110f; baseRadius = 22f; baseMovement = 0f }
            ENEMY_BRUTE -> { baseHp = 220f; baseRadius = 32f; baseMovement = 34f }
            ENEMY_BOSS -> { baseHp = 3600f; baseRadius = 70f; baseMovement = 28f }
            else -> { baseHp = 30f; baseRadius = 12f; baseMovement = 72f }
        }
        val hp = baseHp * scaling * difficulty * if (elite) 1.8f else 1f
        return Enemy(
            x = x,
            y = y,
            kind = kind,
            hp = hp,
            maxHp = hp,
            radius = scaled(baseRadius) * if (elite) 1.16f else 1f,
            speed = scaled(baseMovement),
            angle = randomRange(0f, TAU),
            phase = randomRange(0f, TAU),
            elite = elite,
        )
    }

    private fun resetBodyAtHead() {
        playerSegments.clear()
        repeat(targetLength.toInt().coerceAtLeast(20)) { index ->
            playerSegments += Point(
                headX - cos(angle) * index * segmentSpacing,
                headY - sin(angle) * index * segmentSpacing,
            )
        }
    }

    private fun advanceBody(points: MutableList<Point>, x: Float, y: Float, maximum: Int) {
        val head = if (points.size >= maximum && points.isNotEmpty()) {
            points.removeAt(points.lastIndex)
        } else {
            Point(x, y)
        }
        head.x = x
        head.y = y
        points.add(0, head)
        while (points.size > maximum) points.removeAt(points.lastIndex)
    }

    private fun addCombo() {
        combo += 1
        comboTimer = 6f
        scoreMultiplier = 1f + min(2f, combo * .12f)
    }

    private fun damageMultiplier(): Float {
        val archetypeDamage = when (archetypeIndex) { 0 -> 1.04f; 2 -> 1.08f; 3 -> .96f; else -> 1f }
        val mapBuff = if (mapBuffTimer > 0f) 1.28f else 1f
        val longBody = if (targetLength > 80f) 1.12f else 1f
        return (1f + damageRank * .24f) * archetypeDamage * mapBuff * longBody
    }

    private fun spawnParticles(x: Float, y: Float, colorSlot: Int, count: Int, speed: Float) {
        val available = (MAX_PARTICLES - particles.size).coerceAtLeast(0)
        repeat(min(count, available)) {
            val particleAngle = randomRange(0f, TAU)
            val velocity = randomRange(speed * .25f, speed)
            val life = randomRange(.28f, .78f)
            particles += Particle(
                x = x,
                y = y,
                vx = cos(particleAngle) * velocity,
                vy = sin(particleAngle) * velocity,
                size = randomRange(scaled(2f), scaled(6f)),
                life = life,
                maxLife = life,
                colorSlot = colorSlot,
            )
        }
    }

    private fun angleDelta(from: Float, to: Float): Float = atan2(sin(to - from), cos(to - from))
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float = hypot(x1 - x2, y1 - y2)
    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
    private fun randomRange(minimum: Float, maximum: Float): Float = minimum + (maximum - minimum) * random.nextFloat()
    private fun scaled(value: Float): Float = value * worldScale

    private companion object {
        const val FIXED_STEP = 1f / 60f
        const val WEB_WORLD_WIDTH = 2400f
        const val TAU = 6.2831855f
        const val MAX_PARTICLES = 180
        const val SPECIAL_NONE = 0
        const val SPECIAL_HEAL = 1
        const val SPECIAL_MUTAGEN = 2
        const val SPECIAL_CHARGE = 3
        const val ENEMY_MITE = 0
        const val ENEMY_DRONE = 1
        const val ENEMY_TURRET = 2
        const val ENEMY_BRUTE = 3
        const val ENEMY_BOSS = 4
        const val COLOR_PRIMARY = 0
        const val COLOR_SECONDARY = 1
        const val COLOR_DANGER = 2
        const val COLOR_GOLD = 3
        const val COLOR_WHITE = 4
        val WILD_THORNS = arrayOf(
            floatArrayOf(460f, 360f, 82f),
            floatArrayOf(1850f, 330f, 95f),
            floatArrayOf(760f, 1130f, 88f),
            floatArrayOf(1980f, 1030f, 72f),
        )
        val LAB_POOLS = arrayOf(
            floatArrayOf(470f, 340f, 96f),
            floatArrayOf(1810f, 380f, 112f),
            floatArrayOf(650f, 1110f, 105f),
            floatArrayOf(1910f, 1060f, 92f),
        )
    }
}
