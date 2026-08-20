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
    private var runtimePerformanceTier = 0

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
    ): Int {
        viewportWidth = width.coerceAtLeast(1).toFloat()
        viewportHeight = height.coerceAtLeast(1).toFloat()
        runtimePerformanceTier = performanceTier.coerceIn(0, 2)
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
        val offset = (time * dp(7f)) % gap
        var x = -viewportHeight * .28f - gap + offset
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
        var y = offset
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
            val x = seededX(index) * viewportWidth
            val y = seededY(index + 8) * viewportHeight
            val radius = dp(24f + (index % 4) * 13f) + sin(time * .6f + index) * dp(5f)
            addRing(x, y, radius, palette.mapPrimary, .10f + index * .008f)
        }
    }

    private fun buildLab(time: Float) {
        val stripe = dp(70f * (1f + runtimePerformanceTier * .28f))
        val offset = (time * dp(10f)) % stripe
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

        for (index in 0 until foodCount) {
            val cursor = foodOffset + index * 3
            if (cursor + 2 >= snapshotSize) break
            val value = snapshot[cursor + 2].toInt().coerceIn(1, 3)
            val color = when (value) {
                3 -> Color.rgb(255, 207, 74)
                2 -> palette.mapSecondary
                else -> palette.mapPrimary
            }
            if (config.effects != "compact" && runtimePerformanceTier < 2) {
                addCircle(snapshot[cursor], snapshot[cursor + 1], dp(9f + value * 2f), color, .12f)
            }
            addCircle(snapshot[cursor], snapshot[cursor + 1], dp(4f + value), color, 1f)
        }

        var botCursor = foodOffset + foodCount * 3
        for (botIndex in 0 until botCount) {
            if (botCursor + 4 >= snapshotSize) break
            val headX = snapshot[botCursor]
            val headY = snapshot[botCursor + 1]
            val botSegments = snapshot[botCursor + 3].toInt().coerceAtLeast(0)
            val alive = snapshot[botCursor + 4] > .5f
            botCursor += 5
            val color = BOT_COLORS[botIndex % BOT_COLORS.size]
            if (alive) {
                for (segmentIndex in botSegments - 1 downTo 0) {
                    val cursor = botCursor + segmentIndex * 2
                    if (cursor + 1 >= snapshotSize) continue
                    val progress = if (botSegments <= 1) 0f else segmentIndex.toFloat() / (botSegments - 1)
                    addCircle(
                        snapshot[cursor],
                        snapshot[cursor + 1],
                        dp(9f) * (1f - progress * .28f),
                        mix(color, palette.mapSecondary, progress * .62f),
                        1f,
                    )
                }
                addCircle(headX, headY, dp(9.5f), Color.WHITE, 1f)
                addCircle(headX, headY, dp(7.2f), color, 1f)
            }
            botCursor += botSegments * 2
        }

        for (index in segmentCount - 1 downTo 0) {
            val cursor = SNAPSHOT_HEADER_SIZE + index * 2
            if (cursor + 1 >= snapshotSize) continue
            val progress = if (segmentCount <= 1) 0f else index.toFloat() / (segmentCount - 1)
            val color = mix(palette.snakePrimary, palette.snakeSecondary, progress)
            val radius = dp(11f) * (1f - progress * .34f)
            if (config.effects == "luxury" && runtimePerformanceTier == 0) {
                addCircle(snapshot[cursor], snapshot[cursor + 1], radius * 1.75f, color, .14f)
            }
            addCircle(snapshot[cursor], snapshot[cursor + 1], radius, color, 1f)
        }

        val headX = snapshot[0]
        val headY = snapshot[1]
        addCircle(headX, headY, dp(12f), Color.WHITE, 1f)
        addCircle(headX, headY, dp(9f), palette.snakePrimary, 1f)
        val directionLength = kotlin.math.hypot(directionX, directionY).coerceAtLeast(.001f)
        val forwardX = directionX / directionLength * dp(6f)
        val forwardY = directionY / directionLength * dp(6f)
        val sideX = -directionY / directionLength * dp(3f)
        val sideY = directionX / directionLength * dp(3f)
        val eyeColor = Color.rgb(4, 10, 20)
        addCircle(headX + forwardX + sideX, headY + forwardY + sideY, dp(1.5f), eyeColor, 1f)
        addCircle(headX + forwardX - sideX, headY + forwardY - sideY, dp(1.5f), eyeColor, 1f)
    }

    private fun buildSkillEffects(snapshot: FloatArray, pulseStartedMs: Long, shieldStartedMs: Long, nowMs: Long) {
        val headX = snapshot[0]
        val headY = snapshot[1]
        val pulseAge = nowMs - pulseStartedMs
        if (pulseAge in 0L..620L) {
            val progress = pulseAge / 620f
            addRing(headX, headY, dp(30f) + progress * dp(165f), palette.mapPrimary, (1f - progress) * .82f)
            if (config.effects == "luxury" && runtimePerformanceTier == 0) {
                addRing(headX, headY, dp(20f) + progress * dp(125f), palette.mapSecondary, (1f - progress) * .51f)
            }
        }
        val shieldAge = nowMs - shieldStartedMs
        if (shieldAge in 0L..3200L) {
            addRing(
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
        private const val SNAPSHOT_HEADER_SIZE = 14
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
    }
}
