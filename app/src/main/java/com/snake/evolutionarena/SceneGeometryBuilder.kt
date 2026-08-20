package com.snake.evolutionarena

import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Builds one interleaved, allocation-free triangle batch shared by GLES and Vulkan. */
class SceneGeometryBuilder(
    private val config: BattleConfig,
    private val density: Float,
) {
    val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_VERTICES * FLOATS_PER_VERTEX * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val palette = arenaPalette(config)
    private var viewportWidth = 1f
    private var viewportHeight = 1f
    private var cameraX = 0f
    private var cameraY = 0f
    private var runtimePerformanceTier = 0
    private val foodHsv = floatArrayOf(0f, .78f, 1f)

    fun build(
        snapshot: FloatArray,
        snapshotSize: Int,
        width: Int,
        height: Int,
        directionX: Float,
        directionY: Float,
        pulseStartedMs: Long,
        shieldStartedMs: Long,
        nowMs: Long,
        performanceTier: Int,
        cameraX: Float,
        cameraY: Float,
    ): Int {
        viewportWidth = width.coerceAtLeast(1).toFloat()
        viewportHeight = height.coerceAtLeast(1).toFloat()
        runtimePerformanceTier = performanceTier.coerceIn(0, 2)
        this.cameraX = cameraX
        this.cameraY = cameraY
        vertices.clear()

        val time = nowMs / 1000f
        buildArena(time)
        if (snapshotSize >= SNAPSHOT_HEADER_SIZE) {
            buildWorld(snapshot, snapshotSize, directionX, directionY)
            buildSkillEffects(snapshot, pulseStartedMs, shieldStartedMs, nowMs)
        }

        vertices.flip()
        return vertices.limit() / FLOATS_PER_VERTEX
    }

    fun clearRed(): Float = Color.red(palette.clearColor) / 255f
    fun clearGreen(): Float = Color.green(palette.clearColor) / 255f
    fun clearBlue(): Float = Color.blue(palette.clearColor) / 255f

    private fun buildArena(time: Float) {
        val largest = max(viewportWidth, viewportHeight)
        addCircle(
            viewportWidth * .49f,
            viewportHeight * .47f,
            largest * .68f,
            palette.mapPrimary,
            .035f,
        )
        if (runtimePerformanceTier < 2) {
            addCircle(
                viewportWidth * .84f,
                viewportHeight * .12f,
                largest * .31f,
                palette.mapSecondary,
                .045f,
            )
        }

        when (config.mapId) {
            "wilds" -> buildWilds(time)
            "lab" -> buildLab(time)
            else -> buildNeon(time)
        }
        buildAmbientParticles(time)
    }

    private fun buildNeon(time: Float) {
        val baseGap = if (config.quality == "performance") 86f else 58f
        val gap = dp(baseGap * (1f + runtimePerformanceTier * .32f))
        val offsetX = positiveModulo(time * dp(7f) - cameraX * .12f, gap)
        val offsetY = positiveModulo(time * dp(7f) - cameraY * .12f, gap)
        var x = -viewportHeight * .28f - gap + offsetX
        while (x < viewportWidth + gap) {
            addRotatedRect(
                x + viewportHeight * .14f,
                viewportHeight * .5f,
                viewportHeight * .53f,
                dp(.55f),
                -.27f,
                palette.mapPrimary,
                .10f,
            )
            x += gap
        }
        var y = offsetY
        while (y < viewportHeight) {
            addRect(viewportWidth * .5f, y, viewportWidth * .5f, dp(.55f), palette.mapPrimary, .09f)
            y += gap
        }
        val trackY = viewportHeight * .45f + sin(time * .55f) * viewportHeight * .05f
        addRect(viewportWidth * .5f, trackY, viewportWidth * .30f, dp(1.5f), palette.mapSecondary, .28f)
    }

    private fun buildWilds(time: Float) {
        val count = (if (config.quality == "performance") 5 else 9) - runtimePerformanceTier * 2
        for (index in 0 until count) {
            val x = positiveModulo(seededX(index) * viewportWidth - cameraX * .045f, viewportWidth)
            val y = positiveModulo(seededY(index + 8) * viewportHeight - cameraY * .045f, viewportHeight)
            val radius = dp(24f + (index % 4) * 13f) + sin(time * .6f + index) * dp(5f)
            addRing(x, y, radius, palette.mapPrimary, .10f + index * .008f)
        }
    }

    private fun buildLab(time: Float) {
        val stripe = dp(70f * (1f + runtimePerformanceTier * .28f))
        val offset = positiveModulo(time * dp(10f) - (cameraX + cameraY) * .055f, stripe)
        var x = -viewportHeight + offset
        while (x < viewportWidth + viewportHeight) {
            addRotatedRect(
                x + viewportHeight * .5f,
                viewportHeight * .5f,
                viewportHeight * .72f,
                dp(6f),
                -.7853982f,
                palette.mapSecondary,
                .085f,
            )
            x += stripe
        }
        addRect(viewportWidth * .5f, dp(18f), viewportWidth * .5f - dp(18f), dp(1f), palette.mapPrimary, .18f)
        addRect(viewportWidth * .5f, viewportHeight - dp(18f), viewportWidth * .5f - dp(18f), dp(1f), palette.mapPrimary, .18f)
        addRect(dp(18f), viewportHeight * .5f, dp(1f), viewportHeight * .5f - dp(18f), palette.mapPrimary, .18f)
        addRect(viewportWidth - dp(18f), viewportHeight * .5f, dp(1f), viewportHeight * .5f - dp(18f), palette.mapPrimary, .18f)
    }

    private fun buildAmbientParticles(time: Float) {
        val baseCount = when {
            config.quality == "performance" -> 8
            config.effects == "luxury" -> 42
            config.effects == "compact" -> 12
            else -> 24
        }
        val count = (baseCount shr runtimePerformanceTier).coerceAtLeast(5)
        for (index in 0 until count) {
            val x = positiveModulo(seededX(index) + time * (.0014f + index % 4 * .0003f), 1f) * viewportWidth
            val y = positiveModulo(seededY(index) + sin(time * .22f + index) * .018f, 1f) * viewportHeight
            addCircle(
                x,
                y,
                dp(1f + index % 3),
                if (index % 3 == 0) palette.mapSecondary else palette.mapPrimary,
                .14f + index % 5 * .025f,
            )
        }
    }

    private fun buildWorld(snapshot: FloatArray, snapshotSize: Int, directionX: Float, directionY: Float) {
        val segmentCount = snapshot[4].toInt().coerceAtLeast(0)
        val foodCount = snapshot[5].toInt().coerceAtLeast(0)
        val botCount = snapshot[6].toInt().coerceAtLeast(0)
        val foodOffset = SNAPSHOT_HEADER_SIZE + segmentCount * 2

        buildWorldDecor()

        for (index in 0 until foodCount) {
            val cursor = foodOffset + index * BattleSnapshot.FOOD_STRIDE
            if (cursor + BattleSnapshot.FOOD_STRIDE - 1 >= snapshotSize) break
            val value = snapshot[cursor + 2].toInt().coerceIn(1, 5)
            foodHsv[0] = snapshot[cursor + 3]
            val baseColor = Color.HSVToColor(foodHsv)
            val pulse = snapshot[cursor + 4]
            val special = snapshot[cursor + 5].toInt()
            val color = if (special == 0) baseColor else Color.WHITE
            val radius = if (special == 0) {
                dp(3.5f + value * 1.15f) + sin(pulse) * dp(.55f)
            } else {
                dp(9f) + sin(pulse) * dp(1.8f)
            }
            if (config.effects != "compact" && runtimePerformanceTier < 2) {
                addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius * 2.35f, color, .14f)
            }
            addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius, color, 1f)
            if (special != 0) {
                val ringColor = when (special) {
                    1 -> Color.rgb(185, 255, 107)
                    2 -> Color.rgb(255, 207, 74)
                    else -> palette.mapPrimary
                }
                addWorldRing(snapshot[cursor], snapshot[cursor + 1], dp(15f) + sin(pulse) * dp(2f), ringColor, .9f)
            }
        }

        val botsOffset = foodOffset + foodCount * BattleSnapshot.FOOD_STRIDE
        var contentCursor = botsOffset
        repeat(botCount) {
            if (contentCursor + BattleSnapshot.BOT_HEADER_SIZE - 1 >= snapshotSize) return@repeat
            val count = snapshot[contentCursor + 3].toInt().coerceAtLeast(0)
            contentCursor += BattleSnapshot.BOT_HEADER_SIZE + count * 2
        }

        val enemyCount = snapshot[BattleSnapshot.ENEMY_COUNT].toInt().coerceAtLeast(0)
        buildEnemies(snapshot, snapshotSize, contentCursor, enemyCount)
        contentCursor += enemyCount * BattleSnapshot.ENEMY_STRIDE
        val projectileCount = snapshot[BattleSnapshot.PROJECTILE_COUNT].toInt().coerceAtLeast(0)
        buildProjectiles(snapshot, snapshotSize, contentCursor, projectileCount)
        contentCursor += projectileCount * BattleSnapshot.PROJECTILE_STRIDE

        var botCursor = botsOffset
        for (botIndex in 0 until botCount) {
            if (botCursor + 4 >= snapshotSize) break
            val headX = snapshot[botCursor]
            val headY = snapshot[botCursor + 1]
            val botSegments = snapshot[botCursor + 3].toInt().coerceAtLeast(0)
            val alive = snapshot[botCursor + 4] > .5f
            botCursor += BattleSnapshot.BOT_HEADER_SIZE
            val color = BOT_COLORS[botIndex % BOT_COLORS.size]
            if (alive) {
                val bodyStep = when {
                    runtimePerformanceTier >= 2 -> 5
                    config.quality == "quality" -> 2
                    else -> 3
                }
                var segmentIndex = botSegments - 1
                while (segmentIndex >= 0) {
                    val cursor = botCursor + segmentIndex * 2
                    if (cursor + 1 >= snapshotSize) {
                        segmentIndex -= bodyStep
                        continue
                    }
                    val progress = if (botSegments <= 1) 0f else segmentIndex.toFloat() / (botSegments - 1)
                    val radius = dp(9f) * (1f - progress * .22f)
                    if (config.effects == "luxury" && runtimePerformanceTier == 0) {
                        addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius * 1.65f, color, .10f)
                    }
                    addWorldCircle(
                        snapshot[cursor],
                        snapshot[cursor + 1],
                        radius,
                        mix(color, palette.mapSecondary, progress * .62f),
                        1f,
                    )
                    segmentIndex -= bodyStep
                }
                addWorldCircle(headX, headY, dp(13f), Color.WHITE, 1f)
                addWorldCircle(headX, headY, dp(10.5f), color, 1f)
            }
            botCursor += botSegments * 2
        }

        val playerStep = when {
            runtimePerformanceTier >= 2 -> 5
            config.quality == "quality" -> 2
            else -> 3
        }
        var index = segmentCount - 1
        while (index >= 0) {
            val cursor = SNAPSHOT_HEADER_SIZE + index * 2
            if (cursor + 1 >= snapshotSize) {
                index -= playerStep
                continue
            }
            val progress = if (segmentCount <= 1) 0f else index.toFloat() / (segmentCount - 1)
            val color = mix(palette.snakePrimary, palette.snakeSecondary, progress)
            val radius = dp(11f) * (1f - progress * .24f)
            if (config.effects == "luxury" && runtimePerformanceTier == 0) {
                addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius * 1.75f, color, .14f)
            }
            addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius, color, 1f)
            addWorldCircle(snapshot[cursor], snapshot[cursor + 1] - radius * .28f, radius * .24f, Color.WHITE, .24f)
            index -= playerStep
        }

        val headX = snapshot[0]
        val headY = snapshot[1]
        addWorldCircle(headX, headY, dp(15f), Color.WHITE, 1f)
        addWorldCircle(headX, headY, dp(12.2f), palette.snakePrimary, 1f)
        val directionLength = kotlin.math.hypot(directionX, directionY).coerceAtLeast(.001f)
        val forwardX = directionX / directionLength * dp(6f)
        val forwardY = directionY / directionLength * dp(6f)
        val sideX = -directionY / directionLength * dp(3f)
        val sideY = directionX / directionLength * dp(3f)
        val eyeColor = Color.rgb(4, 10, 20)
        addWorldCircle(headX + forwardX + sideX, headY + forwardY + sideY, dp(1.7f), eyeColor, 1f)
        addWorldCircle(headX + forwardX - sideX, headY + forwardY - sideY, dp(1.7f), eyeColor, 1f)

        buildParticles(
            snapshot,
            snapshotSize,
            contentCursor,
            snapshot[BattleSnapshot.PARTICLE_COUNT].toInt().coerceAtLeast(0),
        )
    }

    private fun buildWorldDecor() {
        addWorldRect(viewportWorldCenterX(), dp(20f), viewportWorldHalfWidth(), dp(2f), palette.mapPrimary, .32f)
        addWorldRect(viewportWorldCenterX(), dp(1430f), viewportWorldHalfWidth(), dp(2f), palette.mapPrimary, .32f)
        addWorldRect(dp(20f), dp(725f), dp(2f), dp(705f), palette.mapPrimary, .32f)
        addWorldRect(dp(2380f), dp(725f), dp(2f), dp(705f), palette.mapPrimary, .32f)
        when (config.mapId) {
            "wilds" -> WILD_THORNS.forEachIndexed { index, area ->
                val x = dp(area[0])
                val y = dp(area[1])
                val radius = dp(area[2])
                addWorldCircle(x, y, radius, Color.rgb(185, 255, 107), .09f)
                addWorldRing(x, y, radius, Color.rgb(185, 255, 107), .52f)
                repeat(8) { thorn ->
                    val angle = thorn / 8f * 6.2831855f + index * .37f
                    addWorldCircle(
                        x + cos(angle) * radius * .82f,
                        y + sin(angle) * radius * .82f,
                        dp(4f + thorn % 2 * 2f),
                        Color.rgb(185, 255, 107),
                        .72f,
                    )
                }
            }
            "lab" -> LAB_POOLS.forEach { area ->
                val x = dp(area[0])
                val y = dp(area[1])
                val radius = dp(area[2])
                addWorldCircle(x, y, radius, Color.rgb(255, 90, 119), .12f)
                addWorldCircle(x, y, radius * .62f, Color.rgb(255, 207, 74), .10f)
                addWorldRing(x, y, radius, Color.rgb(255, 207, 74), .48f)
            }
            else -> {
                val laneY = dp(725f)
                addWorldRect(dp(1200f), laneY, dp(1140f), dp(76f), palette.mapSecondary, .075f)
                addWorldRect(dp(1200f), laneY, dp(1140f), dp(2f), palette.mapSecondary, .52f)
                fun addPortal(x: Float) {
                    addWorldCircle(x, laneY, dp(72f), palette.mapPrimary, .11f)
                    addWorldRing(x, laneY, dp(58f), palette.mapPrimary, .88f)
                    addWorldRing(x, laneY, dp(34f), palette.mapSecondary, .54f)
                }
                addPortal(dp(270f))
                addPortal(dp(2130f))
            }
        }
    }

    private fun buildEnemies(snapshot: FloatArray, snapshotSize: Int, offset: Int, count: Int) {
        repeat(count) { index ->
            val cursor = offset + index * BattleSnapshot.ENEMY_STRIDE
            if (cursor + BattleSnapshot.ENEMY_STRIDE - 1 >= snapshotSize) return@repeat
            val x = snapshot[cursor]
            val y = snapshot[cursor + 1]
            val kind = snapshot[cursor + 2].toInt().coerceIn(0, 4)
            val hp = snapshot[cursor + 3].coerceAtLeast(0f)
            val maxHp = snapshot[cursor + 4].coerceAtLeast(1f)
            val radius = snapshot[cursor + 5].coerceAtLeast(dp(5f))
            val elite = snapshot[cursor + 8] > .5f
            val flash = snapshot[cursor + 9] > 0f
            val color = when (kind) {
                0 -> Color.rgb(255, 102, 126)
                1 -> Color.rgb(255, 145, 72)
                2 -> Color.rgb(185, 137, 255)
                3 -> Color.rgb(255, 207, 74)
                else -> palette.mapSecondary
            }
            if (runtimePerformanceTier < 2) addWorldCircle(x, y, radius * 1.75f, color, if (elite) .24f else .13f)
            addWorldCircle(x, y, radius, if (flash) Color.WHITE else color, 1f)
            addWorldRing(x, y, radius * .82f, if (elite) Color.rgb(255, 231, 121) else Color.WHITE, if (elite) .92f else .42f)
            addWorldCircle(x, y, radius * .36f, palette.clearColor, .8f)
            val ratio = (hp / maxHp).coerceIn(0f, 1f)
            addWorldRect(x, y - radius - dp(8f), radius, dp(2f), Color.BLACK, .55f)
            addWorldRect(x - radius * (1f - ratio), y - radius - dp(8f), radius * ratio, dp(2f), if (elite) Color.rgb(255, 225, 104) else Color.rgb(255, 102, 126), .95f)
        }
    }

    private fun buildProjectiles(snapshot: FloatArray, snapshotSize: Int, offset: Int, count: Int) {
        repeat(count) { index ->
            val cursor = offset + index * BattleSnapshot.PROJECTILE_STRIDE
            if (cursor + BattleSnapshot.PROJECTILE_STRIDE - 1 >= snapshotSize) return@repeat
            val color = effectColor(snapshot[cursor + 4].toInt())
            val radius = snapshot[cursor + 2]
            if (runtimePerformanceTier < 2) addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius * 2.8f, color, .16f)
            addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius, color, 1f)
            addWorldCircle(snapshot[cursor], snapshot[cursor + 1], radius * .36f, Color.WHITE, 1f)
        }
    }

    private fun buildParticles(snapshot: FloatArray, snapshotSize: Int, offset: Int, count: Int) {
        if (config.effects == "compact") return
        val limit = when {
            runtimePerformanceTier >= 2 -> minOf(count, 60)
            config.effects == "luxury" -> count
            else -> minOf(count, 120)
        }
        repeat(limit) { index ->
            val cursor = offset + index * BattleSnapshot.PARTICLE_STRIDE
            if (cursor + BattleSnapshot.PARTICLE_STRIDE - 1 >= snapshotSize) return@repeat
            val maxLife = snapshot[cursor + 4].coerceAtLeast(.001f)
            val alpha = (snapshot[cursor + 3] / maxLife).coerceIn(0f, 1f)
            addWorldCircle(
                snapshot[cursor],
                snapshot[cursor + 1],
                snapshot[cursor + 2] * alpha,
                effectColor(snapshot[cursor + 5].toInt()),
                alpha,
            )
        }
    }

    private fun effectColor(slot: Int): Int = when (slot) {
        1 -> palette.mapSecondary
        2 -> Color.rgb(255, 102, 126)
        3 -> Color.rgb(255, 225, 104)
        4 -> Color.WHITE
        else -> palette.mapPrimary
    }

    private fun viewportWorldCenterX(): Float = dp(1200f)
    private fun viewportWorldHalfWidth(): Float = dp(1180f)

    private fun buildSkillEffects(snapshot: FloatArray, pulseStartedMs: Long, shieldStartedMs: Long, nowMs: Long) {
        val headX = snapshot[0]
        val headY = snapshot[1]
        val pulseAge = nowMs - pulseStartedMs
        if (pulseAge in 0L..620L) {
            val progress = pulseAge / 620f
            addWorldRing(headX, headY, dp(30f) + progress * dp(165f), palette.mapPrimary, (1f - progress) * .82f)
            if (config.effects == "luxury" && runtimePerformanceTier == 0) {
                addWorldRing(headX, headY, dp(20f) + progress * dp(125f), palette.mapSecondary, (1f - progress) * .51f)
            }
        }
        val shieldAge = nowMs - shieldStartedMs
        if (shieldAge in 0L..4300L) {
            addWorldRing(
                headX,
                headY,
                dp(28f) + sin(shieldAge / 160f) * dp(2f),
                Color.rgb(185, 255, 107),
                .58f,
            )
        }
    }

    private fun addRect(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float, color: Int, alpha: Float) {
        addShape(centerX, centerY, halfWidth, halfHeight, color, alpha, SHAPE_RECT)
    }

    private fun addCircle(centerX: Float, centerY: Float, radius: Float, color: Int, alpha: Float) {
        addShape(centerX, centerY, radius, radius, color, alpha, SHAPE_CIRCLE)
    }

    private fun addRing(centerX: Float, centerY: Float, radius: Float, color: Int, alpha: Float) {
        addShape(centerX, centerY, radius, radius, color, alpha, SHAPE_RING)
    }

    private fun addWorldCircle(centerX: Float, centerY: Float, radius: Float, color: Int, alpha: Float) {
        val screenX = centerX - cameraX
        val screenY = centerY - cameraY
        if (!isVisible(screenX, screenY, radius)) return
        addCircle(screenX, screenY, radius, color, alpha)
    }

    private fun addWorldRect(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        color: Int,
        alpha: Float,
    ) {
        val screenX = centerX - cameraX
        val screenY = centerY - cameraY
        if (!isVisible(screenX, screenY, max(halfWidth, halfHeight))) return
        addRect(screenX, screenY, halfWidth, halfHeight, color, alpha)
    }

    private fun addWorldRing(centerX: Float, centerY: Float, radius: Float, color: Int, alpha: Float) {
        val screenX = centerX - cameraX
        val screenY = centerY - cameraY
        if (!isVisible(screenX, screenY, radius)) return
        addRing(screenX, screenY, radius, color, alpha)
    }

    private fun isVisible(centerX: Float, centerY: Float, radius: Float): Boolean =
        centerX + radius >= 0f && centerX - radius <= viewportWidth &&
            centerY + radius >= 0f && centerY - radius <= viewportHeight

    private fun addShape(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        color: Int,
        alpha: Float,
        shape: Float,
    ) {
        if (vertices.remaining() < FLOATS_PER_SHAPE) return
        val left = centerX - halfWidth
        val right = centerX + halfWidth
        val top = centerY - halfHeight
        val bottom = centerY + halfHeight
        putVertex(left, top, -1f, -1f, color, alpha, shape)
        putVertex(right, top, 1f, -1f, color, alpha, shape)
        putVertex(right, bottom, 1f, 1f, color, alpha, shape)
        putVertex(left, top, -1f, -1f, color, alpha, shape)
        putVertex(right, bottom, 1f, 1f, color, alpha, shape)
        putVertex(left, bottom, -1f, 1f, color, alpha, shape)
    }

    private fun addRotatedRect(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        angle: Float,
        color: Int,
        alpha: Float,
    ) {
        if (vertices.remaining() < FLOATS_PER_SHAPE) return
        val cosine = cos(angle)
        val sine = sin(angle)
        val x0 = centerX + (-halfWidth * cosine - -halfHeight * sine)
        val y0 = centerY + (-halfWidth * sine + -halfHeight * cosine)
        val x1 = centerX + (halfWidth * cosine - -halfHeight * sine)
        val y1 = centerY + (halfWidth * sine + -halfHeight * cosine)
        val x2 = centerX + (halfWidth * cosine - halfHeight * sine)
        val y2 = centerY + (halfWidth * sine + halfHeight * cosine)
        val x3 = centerX + (-halfWidth * cosine - halfHeight * sine)
        val y3 = centerY + (-halfWidth * sine + halfHeight * cosine)
        putVertex(x0, y0, -1f, -1f, color, alpha, SHAPE_RECT)
        putVertex(x1, y1, 1f, -1f, color, alpha, SHAPE_RECT)
        putVertex(x2, y2, 1f, 1f, color, alpha, SHAPE_RECT)
        putVertex(x0, y0, -1f, -1f, color, alpha, SHAPE_RECT)
        putVertex(x2, y2, 1f, 1f, color, alpha, SHAPE_RECT)
        putVertex(x3, y3, -1f, 1f, color, alpha, SHAPE_RECT)
    }

    private fun putVertex(x: Float, y: Float, u: Float, v: Float, color: Int, alpha: Float, shape: Float) {
        vertices.put(x / viewportWidth * 2f - 1f)
        vertices.put(1f - y / viewportHeight * 2f)
        vertices.put(u)
        vertices.put(v)
        vertices.put(Color.red(color) / 255f)
        vertices.put(Color.green(color) / 255f)
        vertices.put(Color.blue(color) / 255f)
        vertices.put((Color.alpha(color) / 255f) * alpha.coerceIn(0f, 1f))
        vertices.put(shape)
    }

    private fun seededX(index: Int): Float = ((index * 47 + 13) % 101) / 100f
    private fun seededY(index: Int): Float = ((index * 71 + 29) % 103) / 102f
    private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus
    private fun dp(value: Float): Float = value * density

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val value = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * value).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * value).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * value).toInt(),
        )
    }

    companion object {
        const val FLOATS_PER_VERTEX = 9
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val MAX_VERTICES = 65_536
        private const val FLOATS_PER_SHAPE = FLOATS_PER_VERTEX * 6
        private const val SNAPSHOT_HEADER_SIZE = BattleSnapshot.HEADER_SIZE
        private const val SHAPE_RECT = 0f
        private const val SHAPE_CIRCLE = 1f
        private const val SHAPE_RING = 2f
        private val BOT_COLORS = intArrayOf(
            Color.rgb(255, 102, 126),
            Color.rgb(255, 206, 87),
            Color.rgb(125, 255, 154),
            Color.rgb(127, 158, 255),
            Color.rgb(233, 116, 255),
            Color.rgb(255, 145, 72),
        )
        private val WILD_THORNS = arrayOf(
            floatArrayOf(460f, 360f, 82f),
            floatArrayOf(1850f, 330f, 95f),
            floatArrayOf(760f, 1130f, 88f),
            floatArrayOf(1980f, 1030f, 72f),
        )
        private val LAB_POOLS = arrayOf(
            floatArrayOf(470f, 340f, 96f),
            floatArrayOf(1810f, 380f, 112f),
            floatArrayOf(650f, 1110f, 105f),
            floatArrayOf(1910f, 1060f, 92f),
        )
    }
}
