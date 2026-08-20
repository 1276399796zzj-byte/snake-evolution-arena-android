package com.snake.evolutionarena

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class BattleView(
    context: Context,
    private val config: BattleConfig,
    private val onExit: () -> Unit,
) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val foodHsv = floatArrayOf(0f, .78f, 1f)
    private val boldTypeface = Typeface.create("sans", Typeface.BOLD)
    private val normalTypeface = Typeface.create("sans", Typeface.NORMAL)
    private val hudTitle = "${config.mapName}  ·  ${config.modeName}"
    private val snapshot = FloatArray(8192)
    private val particleX = FloatArray(56) { index -> ((index * 47 + 13) % 101) / 100f }
    private val particleY = FloatArray(56) { index -> ((index * 71 + 29) % 103) / 102f }
    private val botColors = intArrayOf(
        Color.rgb(255, 102, 126),
        Color.rgb(255, 206, 87),
        Color.rgb(125, 255, 154),
        Color.rgb(127, 158, 255),
        Color.rgb(233, 116, 255),
        Color.rgb(255, 145, 72),
    )

    private var worldRuntime: GameWorldRuntime? = null
    private val worldCamera = WorldCamera()
    private var worldWidth = 1f
    private var worldHeight = 1f
    private var sceneRenderer: ArenaSceneRenderer? = null
    private var actualBackendLabel = "CANVAS · 兼容模式"
    private var backendSummary = ""
    private var backendSummaryTier = -1
    private var running = false
    private var released = false
    private var snapshotSize = 0
    private var lastChoreographerFrameNanos = 0L
    private var renderAccumulatorNanos = 0L
    private var fpsWindowStartNanos = 0L
    private var renderedFrames = 0
    private var actualFps = 0f
    private var loadPerformanceTier = 0
    private var systemPerformanceTier = 0
    private var lowFpsWindows = 0
    private var recoveryWindows = 0
    private var cachedClockSecond = Long.MIN_VALUE
    private var cachedClockText = ""
    private var cachedScore = -1
    private var cachedLevel = -1
    private var cachedScoreText = ""
    private var cachedFps = -1
    private var cachedFpsText = "0 FPS"
    private var cachedEnergy = -1
    private var cachedEnergyText = "BOOST 100%"
    private val cooldownDisplayTenths = IntArray(3) { -1 }
    private val cooldownDisplayText = Array(3) { "" }

    private var backgroundShader: LinearGradient? = null
    private var glowShader: RadialGradient? = null
    private var mapPrimary = Color.rgb(82, 246, 255)
    private var mapSecondary = Color.rgb(155, 92, 255)
    private var snakePrimary = Color.rgb(82, 246, 255)
    private var snakeSecondary = Color.rgb(155, 92, 255)

    private var directionX = 1f
    private var directionY = 0f
    private var movementPointer = MotionEvent.INVALID_POINTER_ID
    private var boostPointer = MotionEvent.INVALID_POINTER_ID
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickThumbX = 0f
    private var joystickThumbY = 0f
    private var boostPressed = false
    private var dashUntilMs = 0L
    private var pulseStartedMs = -10_000L
    private var shieldStartedMs = -10_000L
    private var pulseCooldownUntilMs = 0L
    private var dashCooldownUntilMs = 0L
    private var shieldCooldownUntilMs = 0L

    private val joystickRadius get() = dp(64).toFloat()
    private val boostRadius get() = dp(42).toFloat()
    private val skillRadius get() = dp(29).toFloat()
    private val frameBudgetNanos = 1_000_000_000L / config.targetFps.coerceAtLeast(1)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        configurePalette()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released) resumeGame()
    }

    override fun onDetachedFromWindow() {
        pauseGame()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return

        backgroundShader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(3, 8, 18), mix(mapSecondary, Color.BLACK, .84f), mix(mapPrimary, Color.BLACK, .91f)),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowShader = RadialGradient(
            width * .5f,
            height * .48f,
            max(width, height) * .72f,
            intArrayOf(Color.argb(24, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        resetControlPositions()
        worldCamera.reset()
        worldWidth = dp(WEB_WORLD_WIDTH_DP)
        worldHeight = dp(WEB_WORLD_HEIGHT_DP)

        worldRuntime?.release()
        worldRuntime = GameWorldRuntime.create(
            worldWidth,
            worldHeight,
            dp(WEB_BASE_SPEED_DP),
            dp(PICKUP_RADIUS_DP),
            dp(SEGMENT_SPACING_DP),
            SystemClock.elapsedRealtimeNanos(),
            when (config.mapId) { "wilds" -> 1; "lab" -> 2; else -> 0 },
            when (config.modeId) { "expedition" -> 1; "endless" -> 2; else -> 0 },
            when (config.aiStrength) { "rookie" -> 0; "nightmare" -> 2; else -> 1 },
            when (config.archetypeId) { "bulwark" -> 1; "oracle" -> 2; "scavenger" -> 3; else -> 0 },
            allowNative = config.backend != "compat",
        )
        lastChoreographerFrameNanos = 0L
        renderAccumulatorNanos = 0L
    }

    fun attachSceneRenderer(renderer: ArenaSceneRenderer?, label: String) {
        sceneRenderer = renderer
        actualBackendLabel = label
        backendSummaryTier = -1
        invalidate()
    }

    fun setSystemPerformanceTier(tier: Int) {
        systemPerformanceTier = tier.coerceIn(0, 2)
    }

    fun resumeGame() {
        if (released || running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pauseGame() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun releaseGame() {
        if (released) return
        pauseGame()
        released = true
        worldRuntime?.release()
        worldRuntime = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || released) return
        val boosting = boostPressed || SystemClock.elapsedRealtime() < dashUntilMs
        worldRuntime?.advance(frameTimeNanos, directionX, directionY, boosting)
        val shouldRender = if (lastChoreographerFrameNanos == 0L) {
            true
        } else {
            val delta = (frameTimeNanos - lastChoreographerFrameNanos).coerceIn(0L, 100_000_000L)
            renderAccumulatorNanos += delta
            if (renderAccumulatorNanos >= frameBudgetNanos) {
                renderAccumulatorNanos %= frameBudgetNanos
                true
            } else {
                false
            }
        }
        lastChoreographerFrameNanos = frameTimeNanos
        if (shouldRender) {
            snapshotSize = worldRuntime?.writeSnapshot(snapshot) ?: 0
            if (snapshotSize >= SNAPSHOT_HEADER_SIZE) {
                worldCamera.update(
                    snapshot[0],
                    snapshot[1],
                    width.toFloat(),
                    height.toFloat(),
                    worldWidth,
                    worldHeight,
                )
            }
            sceneRenderer?.submitFrame(
                snapshot,
                snapshotSize,
                directionX,
                directionY,
                pulseStartedMs,
                shieldStartedMs,
                effectivePerformanceTier(),
                worldCamera.x,
                worldCamera.y,
            )
            postInvalidateOnAnimation()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sceneRenderer == null) {
            drawArena(canvas)
            canvas.save()
            canvas.translate(-worldCamera.x, -worldCamera.y)
            if (snapshotSize >= SNAPSHOT_HEADER_SIZE) drawWorld(canvas)
            drawSkillEffects(canvas)
            canvas.restore()
        }
        if (snapshotSize >= SNAPSHOT_HEADER_SIZE) drawMinimap(canvas)
        drawHud(canvas)
        drawControls(canvas)
        if (isUpgradePending()) drawUpgradeOverlay(canvas)
        if (matchStatus() != 0) drawMatchOverlay(canvas)
        updateFps()
    }

    private fun drawArena(canvas: Canvas) {
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = glowShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val now = SystemClock.elapsedRealtime() / 1000f
        when (config.mapId) {
            "wilds" -> drawWilds(canvas, now)
            "lab" -> drawLab(canvas, now)
            else -> drawNeon(canvas, now)
        }
        drawAmbientParticles(canvas, now)
    }

    private fun drawNeon(canvas: Canvas, time: Float) {
        val gap = dp(if (config.quality == "performance") 86 else 58).toFloat()
        val offsetX = positiveModulo(time * dp(7) - worldCamera.x * .12f, gap)
        val offsetY = positiveModulo(time * dp(7) - worldCamera.y * .12f, gap)
        strokePaint.strokeWidth = dp(1).toFloat()
        strokePaint.color = Color.argb(28, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        var x = -gap + offsetX
        while (x < width + gap) {
            canvas.drawLine(x, 0f, x + height * .28f, height.toFloat(), strokePaint)
            x += gap
        }
        var y = offsetY
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, strokePaint)
            y += gap
        }
        strokePaint.strokeWidth = dp(3).toFloat()
        strokePaint.color = Color.argb(75, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
        val trackY = height * .45f + sin(time * .55f) * height * .05f
        canvas.drawLine(width * .2f, trackY, width * .8f, trackY, strokePaint)
    }

    private fun drawWilds(canvas: Canvas, time: Float) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(2).toFloat()
        for (index in 0 until if (config.quality == "performance") 5 else 9) {
            val x = positiveModulo(particleX[index] * width - worldCamera.x * .045f, width.toFloat())
            val y = positiveModulo(particleY[index + 8] * height - worldCamera.y * .045f, height.toFloat())
            val radius = dp(24 + (index % 4) * 13).toFloat() + sin(time * .6f + index) * dp(5)
            strokePaint.color = Color.argb(30 + index * 2, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
            canvas.drawCircle(x, y, radius, strokePaint)
        }
        strokePaint.style = Paint.Style.FILL
    }

    private fun drawLab(canvas: Canvas, time: Float) {
        val stripe = dp(70).toFloat()
        val offset = positiveModulo(time * dp(10) - (worldCamera.x + worldCamera.y) * .055f, stripe)
        strokePaint.strokeWidth = dp(12).toFloat()
        strokePaint.color = Color.argb(24, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
        var x = -height.toFloat() + offset
        while (x < width) {
            canvas.drawLine(x, height.toFloat(), x + height.toFloat(), 0f, strokePaint)
            x += stripe
        }
        strokePaint.strokeWidth = dp(2).toFloat()
        strokePaint.color = Color.argb(48, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        canvas.drawRect(dp(18).toFloat(), dp(18).toFloat(), width - dp(18).toFloat(), height - dp(18).toFloat(), strokePaint)
    }

    private fun drawAmbientParticles(canvas: Canvas, time: Float) {
        val count = when {
            config.quality == "performance" -> 8
            config.effects == "luxury" -> 42
            config.effects == "compact" -> 12
            else -> 24
        }
        for (index in 0 until count) {
            val x = ((particleX[index] + time * (.0014f + index % 4 * .0003f)) % 1f) * width
            val y = ((particleY[index] + sin(time * .22f + index) * .018f + 1f) % 1f) * height
            val color = if (index % 3 == 0) mapSecondary else mapPrimary
            paint.color = Color.argb(35 + index % 5 * 7, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, dp(1 + index % 3).toFloat(), paint)
        }
    }

    private fun drawWorld(canvas: Canvas) {
        val segmentCount = snapshot[4].toInt().coerceAtLeast(0)
        val foodCount = snapshot[5].toInt().coerceAtLeast(0)
        val botCount = snapshot[6].toInt().coerceAtLeast(0)
        val foodOffset = SNAPSHOT_HEADER_SIZE + segmentCount * 2

        drawWorldDecor(canvas)

        for (index in 0 until foodCount) {
            val cursor = foodOffset + index * BattleSnapshot.FOOD_STRIDE
            if (cursor + BattleSnapshot.FOOD_STRIDE - 1 >= snapshotSize) break
            val x = snapshot[cursor]
            val y = snapshot[cursor + 1]
            val value = snapshot[cursor + 2].toInt().coerceIn(1, 5)
            val color = foodColor(snapshot[cursor + 3])
            val pulse = snapshot[cursor + 4]
            val special = snapshot[cursor + 5].toInt()
            val radius = dp(3.5f + value * 1.15f) + sin(pulse) * dp(.55f)
            if (config.effects != "compact") {
                paint.color = Color.argb(42, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x, y, radius * 2.35f, paint)
            }
            paint.color = if (special == 0) color else Color.WHITE
            canvas.drawCircle(x, y, if (special == 0) radius else dp(9f) + sin(pulse) * dp(1.8f), paint)
            if (special != 0) {
                strokePaint.shader = null
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = when (special) {
                    1 -> Color.rgb(185, 255, 107)
                    2 -> Color.rgb(255, 207, 74)
                    else -> Color.rgb(82, 246, 255)
                }
                canvas.drawCircle(x, y, dp(15f) + sin(pulse) * dp(2f), strokePaint)
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
        drawEnemies(canvas, contentCursor, enemyCount)
        contentCursor += enemyCount * BattleSnapshot.ENEMY_STRIDE
        val projectileCount = snapshot[BattleSnapshot.PROJECTILE_COUNT].toInt().coerceAtLeast(0)
        drawProjectiles(canvas, contentCursor, projectileCount)
        contentCursor += projectileCount * BattleSnapshot.PROJECTILE_STRIDE
        val particleOffset = contentCursor

        var botCursor = botsOffset
        for (botIndex in 0 until botCount) {
            if (botCursor + 4 >= snapshotSize) break
            val botHeadX = snapshot[botCursor]
            val botHeadY = snapshot[botCursor + 1]
            val botSegments = snapshot[botCursor + 3].toInt().coerceAtLeast(0)
            val alive = snapshot[botCursor + 4] > .5f
            botCursor += BattleSnapshot.BOT_HEADER_SIZE
            val color = botColors[botIndex % botColors.size]
            if (alive) {
                drawSnakeBody(canvas, botCursor, botSegments, botHeadX, botHeadY, color, mapSecondary, false)
            }
            botCursor += botSegments * 2
        }

        val headX = snapshot[0]
        val headY = snapshot[1]
        drawSnakeBody(canvas, SNAPSHOT_HEADER_SIZE, segmentCount, headX, headY, snakePrimary, snakeSecondary, true)
        drawParticles(
            canvas,
            particleOffset,
            snapshot[BattleSnapshot.PARTICLE_COUNT].toInt().coerceAtLeast(0),
        )
    }

    private fun drawWorldDecor(canvas: Canvas) {
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = Color.argb(92, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        canvas.drawRoundRect(
            dp(18f),
            dp(18f),
            worldWidth - dp(18f),
            worldHeight - dp(18f),
            dp(32f),
            dp(32f),
            strokePaint,
        )
        when (config.mapId) {
            "wilds" -> {
                WILD_THORNS.forEachIndexed { areaIndex, area ->
                    val x = dp(area[0])
                    val y = dp(area[1])
                    val radius = dp(area[2])
                    paint.color = Color.argb(30, 185, 255, 107)
                    canvas.drawCircle(x, y, radius, paint)
                    strokePaint.strokeWidth = dp(2f)
                    strokePaint.color = Color.argb(125, 185, 255, 107)
                    canvas.drawCircle(x, y, radius, strokePaint)
                    repeat(12) { index ->
                        val thornAngle = index / 12f * 6.2831855f + areaIndex * .37f
                        val inner = if (index % 2 == 0) radius * .64f else radius * .82f
                        val outer = radius * (1.06f + index % 3 * .05f)
                        canvas.drawLine(
                            x + cos(thornAngle) * inner,
                            y + sin(thornAngle) * inner,
                            x + cos(thornAngle) * outer,
                            y + sin(thornAngle) * outer,
                            strokePaint,
                        )
                    }
                }
            }
            "lab" -> {
                LAB_POOLS.forEachIndexed { index, area ->
                    val x = dp(area[0])
                    val y = dp(area[1])
                    val radius = dp(area[2])
                    paint.color = Color.argb(28, 255, 90, 119)
                    canvas.drawCircle(x, y, radius, paint)
                    paint.color = Color.argb(34, 255, 207, 74)
                    canvas.drawCircle(x, y, radius * .62f, paint)
                    strokePaint.strokeWidth = dp(2f)
                    strokePaint.color = Color.argb(125 + index * 12, 255, 207, 74)
                    canvas.drawCircle(x, y, radius, strokePaint)
                }
            }
            else -> {
                val laneY = worldHeight * .5f
                paint.color = Color.argb(20, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
                canvas.drawRect(dp(40f), laneY - dp(76f), worldWidth - dp(40f), laneY + dp(76f), paint)
                strokePaint.strokeWidth = dp(4f)
                strokePaint.color = Color.argb(115, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
                canvas.drawLine(dp(60f), laneY, worldWidth - dp(60f), laneY, strokePaint)
                fun drawPortal(portalX: Float) {
                    paint.color = Color.argb(26, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
                    canvas.drawCircle(portalX, laneY, dp(72f), paint)
                    paint.color = Color.argb(34, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
                    canvas.drawCircle(portalX, laneY, dp(48f), paint)
                    strokePaint.strokeWidth = dp(4f)
                    strokePaint.color = Color.argb(220, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
                    canvas.save()
                    canvas.scale(.48f, 1f, portalX, laneY)
                    canvas.drawCircle(portalX, laneY, dp(58f), strokePaint)
                    canvas.restore()
                }
                drawPortal(dp(270f))
                drawPortal(worldWidth - dp(270f))
            }
        }
    }

    private fun drawEnemies(canvas: Canvas, offset: Int, count: Int) {
        for (index in 0 until count) {
            val cursor = offset + index * BattleSnapshot.ENEMY_STRIDE
            if (cursor + BattleSnapshot.ENEMY_STRIDE - 1 >= snapshotSize) break
            val x = snapshot[cursor]
            val y = snapshot[cursor + 1]
            val kind = snapshot[cursor + 2].toInt().coerceIn(0, 4)
            val hp = snapshot[cursor + 3].coerceAtLeast(0f)
            val maxHp = snapshot[cursor + 4].coerceAtLeast(1f)
            val radius = snapshot[cursor + 5].coerceAtLeast(dp(5f))
            val enemyAngle = snapshot[cursor + 6]
            val phase = snapshot[cursor + 7]
            val elite = snapshot[cursor + 8] > .5f
            val flashing = snapshot[cursor + 9] > 0f
            val color = when (kind) {
                0 -> Color.rgb(255, 102, 126)
                1 -> Color.rgb(255, 145, 72)
                2 -> Color.rgb(185, 137, 255)
                3 -> Color.rgb(255, 207, 74)
                else -> mapSecondary
            }
            if (config.effects != "compact") {
                paint.color = Color.argb(if (elite) 64 else 34, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x, y, radius * 1.75f, paint)
            }
            path.reset()
            val sides = when (kind) { 0 -> 6; 1 -> 4; 2 -> 8; 3 -> 7; else -> 10 }
            repeat(sides) { vertex ->
                val vertexAngle = enemyAngle + phase * .08f + vertex / sides.toFloat() * 6.2831855f
                val vertexRadius = radius * if (kind == 0 && vertex % 2 == 0) 1.18f else 1f
                val px = x + cos(vertexAngle) * vertexRadius
                val py = y + sin(vertexAngle) * vertexRadius
                if (vertex == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            paint.color = if (flashing) Color.WHITE else color
            canvas.drawPath(path, paint)
            strokePaint.shader = null
            strokePaint.strokeWidth = dp(if (elite) 3f else 1.5f)
            strokePaint.color = if (elite) Color.rgb(255, 231, 121) else Color.argb(185, 255, 255, 255)
            canvas.drawPath(path, strokePaint)
            paint.color = Color.argb(120, 3, 8, 18)
            canvas.drawCircle(x, y, radius * .38f, paint)
            val barWidth = radius * 1.8f
            val barTop = y - radius - dp(9f)
            paint.color = Color.argb(125, 0, 0, 0)
            canvas.drawRoundRect(x - barWidth * .5f, barTop, x + barWidth * .5f, barTop + dp(4f), dp(2f), dp(2f), paint)
            paint.color = if (elite) Color.rgb(255, 225, 104) else Color.rgb(255, 102, 126)
            canvas.drawRoundRect(
                x - barWidth * .5f,
                barTop,
                x - barWidth * .5f + barWidth * (hp / maxHp).coerceIn(0f, 1f),
                barTop + dp(4f),
                dp(2f),
                dp(2f),
                paint,
            )
        }
    }

    private fun drawProjectiles(canvas: Canvas, offset: Int, count: Int) {
        repeat(count) { index ->
            val cursor = offset + index * BattleSnapshot.PROJECTILE_STRIDE
            if (cursor + BattleSnapshot.PROJECTILE_STRIDE - 1 >= snapshotSize) return@repeat
            val x = snapshot[cursor]
            val y = snapshot[cursor + 1]
            val radius = snapshot[cursor + 2]
            val color = effectColor(snapshot[cursor + 4].toInt())
            paint.color = Color.argb(45, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, radius * 2.8f, paint)
            paint.color = color
            canvas.drawCircle(x, y, radius, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, radius * .36f, paint)
        }
    }

    private fun drawParticles(canvas: Canvas, offset: Int, count: Int) {
        if (config.effects == "compact") return
        val stride = BattleSnapshot.PARTICLE_STRIDE
        val limit = when {
            effectivePerformanceTier() >= 2 -> min(count, 60)
            config.effects == "luxury" -> count
            else -> min(count, 120)
        }
        repeat(limit) { index ->
            val cursor = offset + index * stride
            if (cursor + stride - 1 >= snapshotSize) return@repeat
            val life = snapshot[cursor + 3]
            val maxLife = snapshot[cursor + 4].coerceAtLeast(.001f)
            val alpha = (life / maxLife).coerceIn(0f, 1f)
            val color = effectColor(snapshot[cursor + 5].toInt())
            paint.color = Color.argb((alpha * 230).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(snapshot[cursor], snapshot[cursor + 1], snapshot[cursor + 2] * alpha, paint)
        }
    }

    private fun foodColor(hue: Float): Int {
        foodHsv[0] = hue
        return Color.HSVToColor(foodHsv)
    }

    private fun effectColor(slot: Int): Int = when (slot) {
        1 -> mapSecondary
        2 -> Color.rgb(255, 102, 126)
        3 -> Color.rgb(255, 225, 104)
        4 -> Color.WHITE
        else -> mapPrimary
    }

    private fun drawSnakeBody(
        canvas: Canvas,
        segmentOffset: Int,
        segmentCount: Int,
        headX: Float,
        headY: Float,
        primary: Int,
        secondary: Int,
        player: Boolean,
    ) {
        if (segmentCount <= 0 || segmentOffset + 1 >= snapshotSize) return
        val bodyStep = when {
            effectivePerformanceTier() >= 2 -> 5
            config.quality == "quality" -> 2
            else -> 3
        }
        path.reset()
        path.moveTo(snapshot[segmentOffset], snapshot[segmentOffset + 1])
        var lastX = snapshot[segmentOffset]
        var lastY = snapshot[segmentOffset + 1]
        var index = bodyStep
        while (index < segmentCount) {
            val cursor = segmentOffset + index * 2
            if (cursor + 1 >= snapshotSize) break
            lastX = snapshot[cursor]
            lastY = snapshot[cursor + 1]
            path.lineTo(lastX, lastY)
            index += bodyStep
        }
        val tailCursor = segmentOffset + (segmentCount - 1) * 2
        if (tailCursor + 1 < snapshotSize) {
            lastX = snapshot[tailCursor]
            lastY = snapshot[tailCursor + 1]
            path.lineTo(lastX, lastY)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        if (config.effects == "luxury" && effectivePerformanceTier() == 0) {
            strokePaint.shader = null
            strokePaint.strokeWidth = dp(if (player) 32f else 26f)
            strokePaint.color = Color.argb(34, Color.red(primary), Color.green(primary), Color.blue(primary))
            canvas.drawPath(path, strokePaint)
        }
        strokePaint.strokeWidth = dp(if (player) 22f else 18f)
        strokePaint.shader = if (player) {
            LinearGradient(headX, headY, lastX, lastY, primary, secondary, Shader.TileMode.CLAMP)
        } else {
            null
        }
        if (!player) strokePaint.color = primary
        canvas.drawPath(path, strokePaint)
        strokePaint.shader = null
        strokePaint.strokeWidth = dp(if (player) 3f else 2f)
        strokePaint.color = Color.argb(if (player) 112 else 82, 255, 255, 255)
        canvas.drawPath(path, strokePaint)

        paint.color = Color.WHITE
        canvas.drawCircle(headX, headY, dp(if (player) 15f else 13f), paint)
        paint.color = primary
        canvas.drawCircle(headX, headY, dp(if (player) 12.2f else 10.5f), paint)
        val angle = if (player || segmentCount < 2 || segmentOffset + 3 >= snapshotSize) {
            atan2(directionY, directionX)
        } else {
            atan2(headY - snapshot[segmentOffset + 3], headX - snapshot[segmentOffset + 2])
        }
        val eyeForwardX = cos(angle) * dp(if (player) 7f else 5.8f)
        val eyeForwardY = sin(angle) * dp(if (player) 7f else 5.8f)
        val eyeSideX = -sin(angle) * dp(if (player) 3.5f else 3f)
        val eyeSideY = cos(angle) * dp(if (player) 3.5f else 3f)
        paint.color = Color.rgb(4, 10, 20)
        canvas.drawCircle(headX + eyeForwardX + eyeSideX, headY + eyeForwardY + eyeSideY, dp(1.7f), paint)
        canvas.drawCircle(headX + eyeForwardX - eyeSideX, headY + eyeForwardY - eyeSideY, dp(1.7f), paint)
    }

    private fun drawSkillEffects(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val headX = snapshot.getOrElse(0) { width * .5f }
        val headY = snapshot.getOrElse(1) { height * .5f }
        val pulseAge = now - pulseStartedMs
        if (pulseAge in 0..620) {
            val progress = pulseAge / 620f
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = dp(4).toFloat() * (1f - progress)
            strokePaint.color = Color.argb(((1f - progress) * 210).toInt(), Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
            canvas.drawCircle(headX, headY, dp(30).toFloat() + progress * dp(165), strokePaint)
            if (config.effects == "luxury") {
                strokePaint.color = Color.argb(((1f - progress) * 130).toInt(), Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
                canvas.drawCircle(headX, headY, dp(20).toFloat() + progress * dp(125), strokePaint)
            }
        }
        val shieldAge = now - shieldStartedMs
        if (shieldAge in 0..4300) {
            val phase = sin(shieldAge / 160f) * dp(2)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = dp(3).toFloat()
            strokePaint.color = Color.argb(130, 185, 255, 107)
            canvas.drawCircle(headX, headY, dp(28).toFloat() + phase, strokePaint)
        }
        strokePaint.style = Paint.Style.STROKE
    }

    private fun drawHud(canvas: Canvas) {
        val pad = dp(18).toFloat()
        val score = if (snapshotSize >= 4) snapshot[3].toInt() else 0
        val energy = if (snapshotSize >= 3) snapshot[2].coerceIn(0f, 100f) else 100f
        val level = if (snapshotSize > 9) snapshot[9].toInt().coerceAtLeast(1) else 1
        val experience = if (snapshotSize > 10) snapshot[10].coerceAtLeast(0f) else 0f
        val experienceRequired = if (snapshotSize > 11) snapshot[11].coerceAtLeast(1f) else 40f
        paint.typeface = boldTypeface
        paint.textSize = sp(16)
        paint.color = Color.WHITE
        canvas.drawText(hudTitle, pad + dp(52), pad + sp(18), paint)
        paint.typeface = normalTypeface
        paint.textSize = sp(10)
        paint.color = Color.rgb(152, 171, 199)
        val performanceTier = effectivePerformanceTier()
        if (backendSummaryTier != performanceTier) {
            val adaptiveLabel = if (performanceTier > 0) " · AUTO-L$performanceTier" else ""
            backendSummary = "$actualBackendLabel  ·  ${config.targetFps} FPS  ·  ${config.quality.uppercase()}$adaptiveLabel"
            backendSummaryTier = performanceTier
        }
        canvas.drawText(backendSummary, pad + dp(52), pad + sp(35), paint)

        drawBackButton(canvas, pad, pad)

        val right = width - pad
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = boldTypeface
        paint.textSize = sp(13)
        paint.color = Color.WHITE
        if (cachedScore != score || cachedLevel != level) {
            cachedScore = score
            cachedLevel = level
            cachedScoreText = "LV.$level  ·  得分 $score"
        }
        canvas.drawText(cachedScoreText, right, pad + sp(16), paint)
        paint.textSize = sp(10)
        paint.color = if (actualFps >= config.targetFps * .84f) mapPrimary else Color.rgb(255, 207, 74)
        val roundedFps = actualFps.toInt()
        if (cachedFps != roundedFps) {
            cachedFps = roundedFps
            cachedFpsText = "$roundedFps FPS"
        }
        canvas.drawText(cachedFpsText, right, pad + sp(33), paint)
        if (snapshotSize >= BattleSnapshot.HEADER_SIZE) {
            val armor = snapshot[BattleSnapshot.ARMOR].toInt().coerceAtLeast(0)
            val maxArmor = snapshot[BattleSnapshot.MAX_ARMOR].toInt().coerceAtLeast(0)
            val kills = snapshot[BattleSnapshot.KILLS].toInt().coerceAtLeast(0)
            val combo = snapshot[BattleSnapshot.COMBO].toInt().coerceAtLeast(0)
            paint.textSize = sp(8)
            paint.color = Color.rgb(174, 190, 215)
            canvas.drawText("护甲 $armor/$maxArmor  ·  击破 $kills${if (combo > 1) "  ·  ${combo}连杀" else ""}", right, pad + sp(46), paint)
        }
        paint.textAlign = Paint.Align.LEFT

        val barWidth = dp(140).toFloat()
        val barHeight = dp(7).toFloat()
        val barX = width * .5f - barWidth * .5f
        val barY = pad
        paint.color = Color.argb(95, 255, 255, 255)
        canvas.drawRoundRect(barX, barY, barX + barWidth, barY + barHeight, barHeight, barHeight, paint)
        paint.color = mapPrimary
        canvas.drawRoundRect(barX, barY, barX + barWidth * (energy / 100f), barY + barHeight, barHeight, barHeight, paint)
        paint.textSize = sp(9)
        paint.color = Color.rgb(190, 205, 226)
        val roundedEnergy = energy.toInt()
        if (cachedEnergy != roundedEnergy) {
            cachedEnergy = roundedEnergy
            cachedEnergyText = "BOOST $roundedEnergy%"
        }
        canvas.drawText(cachedEnergyText, barX, barY + dp(20), paint)

        val experienceY = barY + dp(27)
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawRoundRect(barX, experienceY, barX + barWidth, experienceY + dp(4), dp(4).toFloat(), dp(4).toFloat(), paint)
        paint.color = mapSecondary
        canvas.drawRoundRect(
            barX,
            experienceY,
            barX + barWidth * (experience / experienceRequired).coerceIn(0f, 1f),
            experienceY + dp(4),
            dp(4).toFloat(),
            dp(4).toFloat(),
            paint,
        )

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = boldTypeface
        paint.textSize = sp(10)
        paint.color = Color.argb(155, 215, 227, 242)
        canvas.drawText(modeClock(), width * .5f, height - dp(18).toFloat(), paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawMinimap(canvas: Canvas) {
        if (config.quality == "performance" || effectivePerformanceTier() >= 2) return
        val mapWidth = dp(116f)
        val mapHeight = dp(68f)
        val left = width - mapWidth - dp(20f)
        val top = dp(66f)
        paint.color = Color.argb(142, 3, 9, 21)
        canvas.drawRoundRect(left, top, left + mapWidth, top + mapHeight, dp(12f), dp(12f), paint)
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(105, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        canvas.drawRoundRect(left, top, left + mapWidth, top + mapHeight, dp(12f), dp(12f), strokePaint)
        val sourceWidth = snapshot[BattleSnapshot.WORLD_WIDTH].coerceAtLeast(1f)
        val sourceHeight = snapshot[BattleSnapshot.WORLD_HEIGHT].coerceAtLeast(1f)
        val inset = dp(6f)
        fun miniX(worldX: Float) = left + inset + worldX / sourceWidth * (mapWidth - inset * 2f)
        fun miniY(worldY: Float) = top + inset + worldY / sourceHeight * (mapHeight - inset * 2f)

        val segmentCount = snapshot[4].toInt().coerceAtLeast(0)
        val foodCount = snapshot[5].toInt().coerceAtLeast(0)
        val botCount = snapshot[6].toInt().coerceAtLeast(0)
        var cursor = SNAPSHOT_HEADER_SIZE + segmentCount * 2 + foodCount * BattleSnapshot.FOOD_STRIDE
        paint.color = Color.argb(205, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
        repeat(botCount) {
            if (cursor + BattleSnapshot.BOT_HEADER_SIZE - 1 >= snapshotSize) return@repeat
            val segments = snapshot[cursor + 3].toInt().coerceAtLeast(0)
            if (snapshot[cursor + 4] > .5f) canvas.drawCircle(miniX(snapshot[cursor]), miniY(snapshot[cursor + 1]), dp(1.8f), paint)
            cursor += BattleSnapshot.BOT_HEADER_SIZE + segments * 2
        }
        val enemyCount = snapshot[BattleSnapshot.ENEMY_COUNT].toInt().coerceAtLeast(0)
        paint.color = Color.rgb(255, 102, 126)
        repeat(enemyCount) { index ->
            val enemyCursor = cursor + index * BattleSnapshot.ENEMY_STRIDE
            if (enemyCursor + 1 >= snapshotSize) return@repeat
            canvas.drawCircle(miniX(snapshot[enemyCursor]), miniY(snapshot[enemyCursor + 1]), dp(1.4f), paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(miniX(snapshot[0]), miniY(snapshot[1]), dp(3f), paint)
        paint.color = snakePrimary
        canvas.drawCircle(miniX(snapshot[0]), miniY(snapshot[1]), dp(2f), paint)
    }

    private fun drawBackButton(canvas: Canvas, x: Float, y: Float) {
        paint.color = Color.argb(105, 8, 17, 34)
        canvas.drawCircle(x + dp(20), y + dp(20), dp(20).toFloat(), paint)
        strokePaint.strokeWidth = dp(2).toFloat()
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = Color.WHITE
        path.reset()
        path.moveTo(x + dp(24), y + dp(12))
        path.lineTo(x + dp(15), y + dp(20))
        path.lineTo(x + dp(24), y + dp(28))
        canvas.drawPath(path, strokePaint)
    }

    private fun drawControls(canvas: Canvas) {
        val active = movementPointer != MotionEvent.INVALID_POINTER_ID
        paint.color = Color.argb(if (active) 52 else 34, 82, 246, 255)
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, paint)
        strokePaint.strokeWidth = dp(2).toFloat()
        strokePaint.color = Color.argb(if (active) 205 else 115, 82, 246, 255)
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, strokePaint)
        paint.color = Color.argb(205, 82, 246, 255)
        canvas.drawCircle(joystickThumbX, joystickThumbY, dp(25).toFloat(), paint)
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(joystickThumbX - dp(5), joystickThumbY - dp(5), dp(8).toFloat(), paint)

        val boostX = boostCenterX()
        val boostY = boostCenterY()
        paint.color = if (boostPressed) Color.argb(225, 155, 92, 255) else Color.argb(125, 24, 29, 57)
        canvas.drawCircle(boostX, boostY, boostRadius, paint)
        strokePaint.strokeWidth = dp(2).toFloat()
        strokePaint.color = if (boostPressed) Color.WHITE else mapSecondary
        canvas.drawCircle(boostX, boostY, boostRadius, strokePaint)
        paint.typeface = boldTypeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp(23)
        paint.color = Color.WHITE
        canvas.drawText("⚡", boostX, boostY + sp(8), paint)
        paint.textSize = sp(8)
        paint.color = Color.rgb(198, 209, 228)
        canvas.drawText("加速", boostX, boostY + boostRadius + dp(13), paint)

        val now = SystemClock.elapsedRealtime()
        drawSkillButton(canvas, 0, "◎", "脉冲", now, pulseCooldownUntilMs, 7200L)
        drawSkillButton(canvas, 1, "➤", "折跃", now, dashCooldownUntilMs, 8400L)
        drawSkillButton(canvas, 2, "◇", "护盾", now, shieldCooldownUntilMs, 9200L)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSkillButton(canvas: Canvas, index: Int, icon: String, name: String, now: Long, cooldownUntil: Long, cooldown: Long) {
        val x = skillCenterX(index)
        val y = skillCenterY(index)
        paint.color = Color.argb(145, 11, 21, 43)
        canvas.drawCircle(x, y, skillRadius, paint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(165, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        canvas.drawCircle(x, y, skillRadius, strokePaint)
        paint.typeface = boldTypeface
        paint.textSize = sp(17)
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        canvas.drawText(icon, x, y + sp(6), paint)
        paint.textSize = sp(7)
        paint.color = Color.rgb(184, 201, 223)
        canvas.drawText(name, x, y + skillRadius + dp(10), paint)

        if (cooldownUntil > now) {
            val fraction = ((cooldownUntil - now).toFloat() / cooldown).coerceIn(0f, 1f)
            paint.color = Color.argb(145, 2, 6, 15)
            canvas.drawArc(x - skillRadius, y - skillRadius, x + skillRadius, y + skillRadius, -90f, 360f * fraction, true, paint)
            paint.textSize = sp(9)
            paint.color = Color.WHITE
            val remainingTenths = (((cooldownUntil - now) + 99L) / 100L).toInt()
            if (cooldownDisplayTenths[index] != remainingTenths) {
                cooldownDisplayTenths[index] = remainingTenths
                cooldownDisplayText[index] = "${remainingTenths / 10}.${remainingTenths % 10}"
            }
            canvas.drawText(cooldownDisplayText[index], x, y + sp(3), paint)
        } else {
            cooldownDisplayTenths[index] = -1
        }
    }

    private fun drawUpgradeOverlay(canvas: Canvas) {
        paint.color = Color.argb(222, 3, 7, 17)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = boldTypeface
        paint.textSize = sp(25)
        paint.color = Color.WHITE
        canvas.drawText("选择进化模块", width * .5f, height * .19f, paint)
        paint.typeface = normalTypeface
        paint.textSize = sp(10)
        paint.color = Color.rgb(157, 177, 205)
        canvas.drawText("每次升级需要更多经验，选择期间战斗暂停", width * .5f, height * .19f + dp(24), paint)

        val set = snapshot.getOrElse(13) { 0f }.toInt().coerceIn(0, 3)
        val names = UPGRADE_NAMES[set]
        val descriptions = UPGRADE_DESCRIPTIONS[set]
        for (choice in 0..2) {
            val bounds = upgradeCard(choice)
            paint.color = Color.argb(232, 12, 24, 47)
            canvas.drawRoundRect(bounds[0], bounds[1], bounds[2], bounds[3], dp(18).toFloat(), dp(18).toFloat(), paint)
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = if (choice == 1) mapPrimary else Color.argb(130, Color.red(mapSecondary), Color.green(mapSecondary), Color.blue(mapSecondary))
            canvas.drawRoundRect(bounds[0], bounds[1], bounds[2], bounds[3], dp(18).toFloat(), dp(18).toFloat(), strokePaint)
            paint.textSize = sp(25)
            paint.color = if (choice == 1) mapPrimary else Color.WHITE
            canvas.drawText(UPGRADE_ICONS[choice], (bounds[0] + bounds[2]) * .5f, bounds[1] + dp(48), paint)
            paint.typeface = boldTypeface
            paint.textSize = sp(15)
            paint.color = Color.WHITE
            canvas.drawText(names[choice], (bounds[0] + bounds[2]) * .5f, bounds[1] + dp(82), paint)
            paint.typeface = normalTypeface
            paint.textSize = sp(10)
            paint.color = Color.rgb(165, 185, 211)
            canvas.drawText(descriptions[choice], (bounds[0] + bounds[2]) * .5f, bounds[1] + dp(106), paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawMatchOverlay(canvas: Canvas) {
        val victory = matchStatus() == 1
        paint.color = Color.argb(224, 3, 7, 17)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = boldTypeface
        paint.textSize = sp(38)
        paint.color = if (victory) mapPrimary else Color.rgb(255, 104, 126)
        canvas.drawText(if (victory) "作战完成" else "战蛇失活", width * .5f, height * .40f, paint)
        paint.textSize = sp(16)
        paint.color = Color.WHITE
        canvas.drawText("最终得分 ${snapshot.getOrElse(3) { 0f }.toInt()}  ·  等级 ${snapshot.getOrElse(9) { 1f }.toInt()}", width * .5f, height * .50f, paint)
        paint.typeface = normalTypeface
        paint.textSize = sp(11)
        paint.color = Color.rgb(157, 177, 205)
        canvas.drawText("轻触任意位置返回竖屏大厅", width * .5f, height * .59f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun upgradeChoiceAt(x: Float, y: Float): Int {
        for (choice in 0..2) {
            val bounds = upgradeCard(choice)
            if (x in bounds[0]..bounds[2] && y in bounds[1]..bounds[3]) return choice
        }
        return -1
    }

    private fun upgradeCard(choice: Int): FloatArray {
        val gap = dp(16).toFloat()
        val cardWidth = minOf(dp(210).toFloat(), (width - dp(120).toFloat() - gap * 2f) / 3f)
        val totalWidth = cardWidth * 3f + gap * 2f
        val left = (width - totalWidth) * .5f + choice * (cardWidth + gap)
        val top = height * .31f
        return floatArrayOf(left, top, left + cardWidth, top + minOf(dp(150).toFloat(), height * .42f))
    }

    private fun isUpgradePending(): Boolean = snapshotSize > 12 && snapshot[12] > .5f
    private fun matchStatus(): Int = if (snapshotSize > 8) snapshot[8].toInt() else 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (matchStatus() != 0) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onExit()
            return true
        }
        if (isUpgradePending()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val choice = upgradeChoiceAt(event.getX(event.actionIndex), event.getY(event.actionIndex))
                if (choice >= 0) {
                    worldRuntime?.chooseUpgrade(choice)
                    haptic(HapticFeedbackConstants.CONFIRM)
                    invalidate()
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (x <= dp(76) && y <= dp(76)) {
                    haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                    onExit()
                    return true
                }
                val skillIndex = hitSkill(x, y)
                if (skillIndex >= 0) {
                    activateSkill(skillIndex)
                    return true
                }
                if (distance(x, y, boostCenterX(), boostCenterY()) <= boostRadius * 1.45f &&
                    boostPointer == MotionEvent.INVALID_POINTER_ID
                ) {
                    boostPointer = pointerId
                    boostPressed = true
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                if (isMovementSide(x) && movementPointer == MotionEvent.INVALID_POINTER_ID) {
                    movementPointer = pointerId
                    if (config.floatingJoystick) {
                        joystickCenterX = x.coerceIn(joystickRadius, width - joystickRadius)
                        joystickCenterY = y.coerceIn(height * .42f, height - joystickRadius)
                    }
                    updateJoystick(x, y)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    when (event.getPointerId(index)) {
                        movementPointer -> updateJoystick(event.getX(index), event.getY(index))
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                releasePointer(pointerId)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                movementPointer = MotionEvent.INVALID_POINTER_ID
                boostPointer = MotionEvent.INVALID_POINTER_ID
                boostPressed = false
                resetControlPositions()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joystickCenterX
        val dy = y - joystickCenterY
        val length = hypot(dx, dy)
        val maxDistance = joystickRadius * .72f
        val scale = if (length > maxDistance && length > 0f) maxDistance / length else 1f
        joystickThumbX = joystickCenterX + dx * scale
        joystickThumbY = joystickCenterY + dy * scale
        if (length > dp(8)) {
            directionX = dx / length
            directionY = dy / length
        }
        invalidate()
    }

    private fun releasePointer(pointerId: Int) {
        if (pointerId == movementPointer) {
            movementPointer = MotionEvent.INVALID_POINTER_ID
            resetControlPositions()
        }
        if (pointerId == boostPointer) {
            boostPointer = MotionEvent.INVALID_POINTER_ID
            boostPressed = false
        }
        invalidate()
    }

    private fun activateSkill(index: Int) {
        val now = SystemClock.elapsedRealtime()
        var activated = false
        when (index) {
            0 -> if (now >= pulseCooldownUntilMs) {
                pulseStartedMs = now
                pulseCooldownUntilMs = now + 7200L
                haptic(HapticFeedbackConstants.CONFIRM)
                activated = true
            }
            1 -> if (now >= dashCooldownUntilMs) {
                dashUntilMs = now + 460L
                dashCooldownUntilMs = now + 8400L
                haptic(HapticFeedbackConstants.CONFIRM)
                activated = true
            }
            2 -> if (now >= shieldCooldownUntilMs) {
                shieldStartedMs = now
                shieldCooldownUntilMs = now + 9200L
                haptic(HapticFeedbackConstants.CONFIRM)
                activated = true
            }
        }
        if (activated) worldRuntime?.activateAbility(index)
        invalidate()
    }

    private fun hitSkill(x: Float, y: Float): Int {
        for (index in 0..2) {
            if (distance(x, y, skillCenterX(index), skillCenterY(index)) <= skillRadius * 1.35f) return index
        }
        return -1
    }

    private fun skillCenterX(index: Int): Float {
        val boostX = boostCenterX()
        val direction = if (config.leftHanded) 1f else -1f
        return when (index) {
            0 -> boostX + direction * dp(76)
            1 -> boostX + direction * dp(61)
            else -> boostX
        }
    }

    private fun skillCenterY(index: Int): Float = when (index) {
        0 -> height - dp(76).toFloat()
        1 -> height - dp(142).toFloat()
        else -> height - dp(166).toFloat()
    }

    private fun resetControlPositions() {
        joystickCenterX = if (config.leftHanded) width - dp(104).toFloat() else dp(104).toFloat()
        joystickCenterY = height - dp(104).toFloat()
        joystickThumbX = joystickCenterX
        joystickThumbY = joystickCenterY
    }

    private fun boostCenterX(): Float = if (config.leftHanded) dp(96).toFloat() else width - dp(96).toFloat()
    private fun boostCenterY(): Float = height - dp(92).toFloat()
    private fun isMovementSide(x: Float): Boolean = if (config.leftHanded) x >= width * .45f else x <= width * .55f

    private fun modeClock(): String {
        val elapsedSeconds = if (snapshotSize > 7) snapshot[7].toLong().coerceAtLeast(0L) else 0L
        if (cachedClockSecond == elapsedSeconds) return cachedClockText
        cachedClockSecond = elapsedSeconds
        val total = when (config.modeId) {
            "blitz" -> 180L
            "expedition" -> 600L
            else -> -1L
        }
        cachedClockText = if (total < 0L) {
            "无尽猎场  ·  ${formatTime(elapsedSeconds)}"
        } else {
            "剩余 ${formatTime((total - elapsedSeconds).coerceAtLeast(0L))}"
        }
        return cachedClockText
    }

    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        val minuteText = if (minutes < 10L) "0$minutes" else minutes.toString()
        val secondText = if (remainder < 10L) "0$remainder" else remainder.toString()
        return "$minuteText:$secondText"
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = now
        renderedFrames += 1
        val elapsed = now - fpsWindowStartNanos
        if (elapsed >= 700_000_000L) {
            actualFps = renderedFrames * 1_000_000_000f / elapsed
            updateLoadPerformanceTier()
            renderedFrames = 0
            fpsWindowStartNanos = now
        }
    }

    private fun updateLoadPerformanceTier() {
        if (config.targetFps < 60 || actualFps <= 1f) return
        val ratio = actualFps / config.targetFps
        if (ratio < .82f) {
            lowFpsWindows += 1
            recoveryWindows = 0
            val threshold = if (ratio < .68f) 2 else 4
            if (lowFpsWindows >= threshold && loadPerformanceTier < 2) {
                loadPerformanceTier += 1
                lowFpsWindows = 0
            }
        } else if (ratio > .95f) {
            recoveryWindows += 1
            lowFpsWindows = 0
            if (recoveryWindows >= 9 && loadPerformanceTier > 0) {
                loadPerformanceTier -= 1
                recoveryWindows = 0
            }
        } else {
            lowFpsWindows = 0
            recoveryWindows = 0
        }
    }

    private fun effectivePerformanceTier(): Int = max(loadPerformanceTier, systemPerformanceTier)

    private fun configurePalette() {
        val palette = arenaPalette(config)
        mapPrimary = palette.mapPrimary
        mapSecondary = palette.mapSecondary
        snakePrimary = palette.snakePrimary
        snakeSecondary = palette.snakeSecondary
    }

    private fun haptic(feedback: Int) {
        if (config.haptics) performHapticFeedback(feedback)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float = hypot(x1 - x2, y1 - y2)
    private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus
    private fun mix(first: Int, second: Int, amount: Float): Int {
        val value = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * value).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * value).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * value).toInt(),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        private const val SNAPSHOT_HEADER_SIZE = BattleSnapshot.HEADER_SIZE
        private const val WEB_WORLD_WIDTH_DP = 2400f
        private const val WEB_WORLD_HEIGHT_DP = 1450f
        private const val WEB_BASE_SPEED_DP = 164f
        private const val PICKUP_RADIUS_DP = 32f
        private const val SEGMENT_SPACING_DP = 5.6f
        private val UPGRADE_ICONS = arrayOf("⚡", "◎", "◇")
        private val UPGRADE_NAMES = arrayOf(
            arrayOf("超频突触", "磁暴场", "虚空电容"),
            arrayOf("躯体锻造", "相位尾迹", "纳米修复"),
            arrayOf("积分矩阵", "吞噬本能", "学习协议"),
            arrayOf("高能营养", "迅捷鳞片", "复合电容"),
        )
        private val UPGRADE_DESCRIPTIONS = arrayOf(
            arrayOf("移动速度 +8%", "拾取范围 +18%", "冲刺消耗 -12%"),
            arrayOf("立即增加 8 节", "转向响应 +16%", "能量立即充满"),
            arrayOf("得分效率 +15%", "成长效率 +20%", "经验效率 +18%"),
            arrayOf("资源体积 +12%", "移动速度 +5%", "拾取与回复强化"),
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
