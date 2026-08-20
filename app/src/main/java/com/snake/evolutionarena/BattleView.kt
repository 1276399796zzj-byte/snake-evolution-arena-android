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
import kotlin.math.sin

class BattleView(
    context: Context,
    private val config: BattleConfig,
    private val onExit: () -> Unit,
) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
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

    private var worldHandle = 0L
    private var sceneRenderer: ArenaSceneRenderer? = null
    private var actualBackendLabel = "CANVAS · 兼容模式"
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

        if (worldHandle != 0L) NativeBridge.destroyWorld(worldHandle)
        worldHandle = NativeBridge.createWorld(
            width.toFloat(),
            height.toFloat(),
            SystemClock.elapsedRealtimeNanos(),
            when (config.mapId) { "wilds" -> 1; "lab" -> 2; else -> 0 },
            when (config.modeId) { "expedition" -> 1; "endless" -> 2; else -> 0 },
            when (config.aiStrength) { "rookie" -> 0; "nightmare" -> 2; else -> 1 },
            when (config.archetypeId) { "bulwark" -> 1; "oracle" -> 2; "scavenger" -> 3; else -> 0 },
        )
        lastChoreographerFrameNanos = 0L
        renderAccumulatorNanos = 0L
    }

    fun attachSceneRenderer(renderer: ArenaSceneRenderer?, label: String) {
        sceneRenderer = renderer
        actualBackendLabel = label
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
        if (worldHandle != 0L) {
            NativeBridge.destroyWorld(worldHandle)
            worldHandle = 0L
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || released) return
        if (worldHandle != 0L) {
            val boosting = boostPressed || SystemClock.elapsedRealtime() < dashUntilMs
            NativeBridge.advanceWorld(worldHandle, frameTimeNanos, directionX, directionY, boosting)
        }
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
            if (worldHandle != 0L) snapshotSize = NativeBridge.writeWorldSnapshot(worldHandle, snapshot)
            sceneRenderer?.submitFrame(
                snapshot,
                snapshotSize,
                directionX,
                directionY,
                pulseStartedMs,
                shieldStartedMs,
                effectivePerformanceTier(),
            )
            postInvalidateOnAnimation()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sceneRenderer == null) {
            drawArena(canvas)
            if (snapshotSize >= SNAPSHOT_HEADER_SIZE) drawWorld(canvas)
            drawSkillEffects(canvas)
        }
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
        val offset = (time * dp(7)) % gap
        strokePaint.strokeWidth = dp(1).toFloat()
        strokePaint.color = Color.argb(28, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        var x = -gap + offset
        while (x < width + gap) {
            canvas.drawLine(x, 0f, x + height * .28f, height.toFloat(), strokePaint)
            x += gap
        }
        var y = offset
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
            val x = particleX[index] * width
            val y = particleY[index + 8] * height
            val radius = dp(24 + (index % 4) * 13).toFloat() + sin(time * .6f + index) * dp(5)
            strokePaint.color = Color.argb(30 + index * 2, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
            canvas.drawCircle(x, y, radius, strokePaint)
        }
        strokePaint.style = Paint.Style.FILL
    }

    private fun drawLab(canvas: Canvas, time: Float) {
        val stripe = dp(70).toFloat()
        val offset = (time * dp(10)) % stripe
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

        for (index in 0 until foodCount) {
            val cursor = foodOffset + index * 3
            if (cursor + 2 >= snapshotSize) break
            val x = snapshot[cursor]
            val y = snapshot[cursor + 1]
            val value = snapshot[cursor + 2].toInt().coerceIn(1, 3)
            if (config.effects != "compact") {
                paint.color = Color.argb(35, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
                canvas.drawCircle(x, y, dp(9 + value * 2).toFloat(), paint)
            }
            paint.color = when (value) {
                3 -> Color.rgb(255, 207, 74)
                2 -> mapSecondary
                else -> mapPrimary
            }
            canvas.drawCircle(x, y, dp(4 + value).toFloat(), paint)
        }

        var botCursor = foodOffset + foodCount * 3
        for (botIndex in 0 until botCount) {
            if (botCursor + 4 >= snapshotSize) break
            val botHeadX = snapshot[botCursor]
            val botHeadY = snapshot[botCursor + 1]
            val botSegments = snapshot[botCursor + 3].toInt().coerceAtLeast(0)
            val alive = snapshot[botCursor + 4] > .5f
            botCursor += 5
            val color = botColors[botIndex % botColors.size]
            if (alive) {
                for (segmentIndex in botSegments - 1 downTo 0) {
                    val cursor = botCursor + segmentIndex * 2
                    if (cursor + 1 >= snapshotSize) continue
                    val progress = if (botSegments <= 1) 0f else segmentIndex.toFloat() / (botSegments - 1)
                    paint.color = mix(color, mapSecondary, progress * .62f)
                    canvas.drawCircle(
                        snapshot[cursor],
                        snapshot[cursor + 1],
                        dp(9).toFloat() * (1f - progress * .28f),
                        paint,
                    )
                }
                paint.color = Color.WHITE
                canvas.drawCircle(botHeadX, botHeadY, dp(9.5f), paint)
                paint.color = color
                canvas.drawCircle(botHeadX, botHeadY, dp(7.2f), paint)
            }
            botCursor += botSegments * 2
        }

        for (index in segmentCount - 1 downTo 0) {
            val cursor = SNAPSHOT_HEADER_SIZE + index * 2
            if (cursor + 1 >= snapshotSize) continue
            val progress = if (segmentCount <= 1) 0f else index.toFloat() / (segmentCount - 1)
            paint.color = mix(snakePrimary, snakeSecondary, progress)
            val radius = dp(11).toFloat() * (1f - progress * .34f)
            if (config.effects == "luxury") {
                val glow = paint.color
                paint.color = Color.argb(38, Color.red(glow), Color.green(glow), Color.blue(glow))
                canvas.drawCircle(snapshot[cursor], snapshot[cursor + 1], radius * 1.75f, paint)
                paint.color = glow
            }
            canvas.drawCircle(snapshot[cursor], snapshot[cursor + 1], radius, paint)
        }

        val headX = snapshot[0]
        val headY = snapshot[1]
        paint.color = Color.WHITE
        canvas.drawCircle(headX, headY, dp(12).toFloat(), paint)
        paint.color = snakePrimary
        canvas.drawCircle(headX, headY, dp(9).toFloat(), paint)
        val angle = atan2(directionY, directionX)
        val eyeForwardX = cos(angle) * dp(6)
        val eyeForwardY = sin(angle) * dp(6)
        val eyeSideX = -sin(angle) * dp(3)
        val eyeSideY = cos(angle) * dp(3)
        paint.color = Color.rgb(4, 10, 20)
        canvas.drawCircle(headX + eyeForwardX + eyeSideX, headY + eyeForwardY + eyeSideY, dp(1.5f), paint)
        canvas.drawCircle(headX + eyeForwardX - eyeSideX, headY + eyeForwardY - eyeSideY, dp(1.5f), paint)
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
        if (shieldAge in 0..3200) {
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
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(16)
        paint.color = Color.WHITE
        canvas.drawText("${config.mapName}  ·  ${config.modeName}", pad + dp(52), pad + sp(18), paint)
        paint.typeface = Typeface.create("sans", Typeface.NORMAL)
        paint.textSize = sp(10)
        paint.color = Color.rgb(152, 171, 199)
        val adaptiveLabel = if (effectivePerformanceTier() > 0) " · AUTO-L${effectivePerformanceTier()}" else ""
        canvas.drawText("$actualBackendLabel  ·  ${config.targetFps} FPS  ·  ${config.quality.uppercase()}$adaptiveLabel", pad + dp(52), pad + sp(35), paint)

        drawBackButton(canvas, pad, pad)

        val right = width - pad
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(13)
        paint.color = Color.WHITE
        canvas.drawText("LV.$level  ·  得分 $score", right, pad + sp(16), paint)
        paint.textSize = sp(10)
        paint.color = if (actualFps >= config.targetFps * .84f) mapPrimary else Color.rgb(255, 207, 74)
        canvas.drawText("${actualFps.toInt()} FPS", right, pad + sp(33), paint)
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
        canvas.drawText("BOOST ${energy.toInt()}%", barX, barY + dp(20), paint)

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
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(10)
        paint.color = Color.argb(155, 215, 227, 242)
        canvas.drawText(modeClock(), width * .5f, height - dp(18).toFloat(), paint)
        paint.textAlign = Paint.Align.LEFT
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
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp(23)
        paint.color = Color.WHITE
        canvas.drawText("⚡", boostX, boostY + sp(8), paint)
        paint.textSize = sp(8)
        paint.color = Color.rgb(198, 209, 228)
        canvas.drawText("加速", boostX, boostY + boostRadius + dp(13), paint)

        val now = SystemClock.elapsedRealtime()
        drawSkillButton(canvas, 0, "◎", "脉冲", now, pulseCooldownUntilMs, 4800L)
        drawSkillButton(canvas, 1, "➤", "折跃", now, dashCooldownUntilMs, 3900L)
        drawSkillButton(canvas, 2, "◇", "护盾", now, shieldCooldownUntilMs, 7200L)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSkillButton(canvas: Canvas, index: Int, icon: String, name: String, now: Long, cooldownUntil: Long, cooldown: Long) {
        val (x, y) = skillCenter(index)
        paint.color = Color.argb(145, 11, 21, 43)
        canvas.drawCircle(x, y, skillRadius, paint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = Color.argb(165, Color.red(mapPrimary), Color.green(mapPrimary), Color.blue(mapPrimary))
        canvas.drawCircle(x, y, skillRadius, strokePaint)
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
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
            canvas.drawText("%.1f".format((cooldownUntil - now) / 1000f), x, y + sp(3), paint)
        }
    }

    private fun drawUpgradeOverlay(canvas: Canvas) {
        paint.color = Color.argb(222, 3, 7, 17)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(25)
        paint.color = Color.WHITE
        canvas.drawText("选择进化模块", width * .5f, height * .19f, paint)
        paint.typeface = Typeface.create("sans", Typeface.NORMAL)
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
            paint.typeface = Typeface.create("sans", Typeface.BOLD)
            paint.textSize = sp(15)
            paint.color = Color.WHITE
            canvas.drawText(names[choice], (bounds[0] + bounds[2]) * .5f, bounds[1] + dp(82), paint)
            paint.typeface = Typeface.create("sans", Typeface.NORMAL)
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
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(38)
        paint.color = if (victory) mapPrimary else Color.rgb(255, 104, 126)
        canvas.drawText(if (victory) "作战完成" else "战蛇失活", width * .5f, height * .40f, paint)
        paint.textSize = sp(16)
        paint.color = Color.WHITE
        canvas.drawText("最终得分 ${snapshot.getOrElse(3) { 0f }.toInt()}  ·  等级 ${snapshot.getOrElse(9) { 1f }.toInt()}", width * .5f, height * .50f, paint)
        paint.typeface = Typeface.create("sans", Typeface.NORMAL)
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
                if (choice >= 0 && worldHandle != 0L) {
                    NativeBridge.chooseUpgrade(worldHandle, choice)
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
        when (index) {
            0 -> if (now >= pulseCooldownUntilMs) {
                pulseStartedMs = now
                pulseCooldownUntilMs = now + 4800L
                haptic(HapticFeedbackConstants.CONFIRM)
            }
            1 -> if (now >= dashCooldownUntilMs) {
                dashUntilMs = now + 360L
                dashCooldownUntilMs = now + 3900L
                haptic(HapticFeedbackConstants.CONFIRM)
            }
            2 -> if (now >= shieldCooldownUntilMs) {
                shieldStartedMs = now
                shieldCooldownUntilMs = now + 7200L
                haptic(HapticFeedbackConstants.CONFIRM)
            }
        }
        invalidate()
    }

    private fun hitSkill(x: Float, y: Float): Int {
        for (index in 0..2) {
            val center = skillCenter(index)
            if (distance(x, y, center.first, center.second) <= skillRadius * 1.35f) return index
        }
        return -1
    }

    private fun skillCenter(index: Int): Pair<Float, Float> {
        val boostX = boostCenterX()
        val direction = if (config.leftHanded) 1f else -1f
        return when (index) {
            0 -> Pair(boostX + direction * dp(76), height - dp(76).toFloat())
            1 -> Pair(boostX + direction * dp(61), height - dp(142).toFloat())
            else -> Pair(boostX, height - dp(166).toFloat())
        }
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
        val total = when (config.modeId) {
            "blitz" -> 180L
            "expedition" -> 600L
            else -> -1L
        }
        if (total < 0L) return "无尽猎场  ·  ${formatTime(elapsedSeconds)}"
        return "剩余 ${formatTime((total - elapsedSeconds).coerceAtLeast(0L))}"
    }

    private fun formatTime(seconds: Long): String = "%02d:%02d".format(seconds / 60L, seconds % 60L)

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
        private const val SNAPSHOT_HEADER_SIZE = 14
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
    }
}
