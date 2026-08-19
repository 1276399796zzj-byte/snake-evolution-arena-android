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
    private val snapshot = FloatArray(1024)
    private val particleX = FloatArray(56) { index -> ((index * 47 + 13) % 101) / 100f }
    private val particleY = FloatArray(56) { index -> ((index * 71 + 29) % 103) / 102f }

    private var worldHandle = 0L
    private var running = false
    private var released = false
    private var snapshotSize = 0
    private var lastRenderedFrameNanos = 0L
    private var fpsWindowStartNanos = 0L
    private var renderedFrames = 0
    private var actualFps = 0f
    private var gameStartedAt = 0L

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
        worldHandle = NativeBridge.createWorld(width.toFloat(), height.toFloat(), SystemClock.elapsedRealtimeNanos())
        gameStartedAt = SystemClock.elapsedRealtime()
        lastRenderedFrameNanos = 0L
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
        if (lastRenderedFrameNanos == 0L || frameTimeNanos - lastRenderedFrameNanos >= (frameBudgetNanos * .84f).toLong()) {
            lastRenderedFrameNanos = frameTimeNanos
            postInvalidateOnAnimation()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawArena(canvas)
        if (worldHandle != 0L) snapshotSize = NativeBridge.writeWorldSnapshot(worldHandle, snapshot)
        if (snapshotSize >= 6) drawWorld(canvas)
        drawSkillEffects(canvas)
        drawHud(canvas)
        drawControls(canvas)
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
        val foodOffset = 6 + segmentCount * 2

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

        for (index in segmentCount - 1 downTo 0) {
            val cursor = 6 + index * 2
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
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(16)
        paint.color = Color.WHITE
        canvas.drawText("${config.mapName}  ·  ${config.modeName}", pad + dp(52), pad + sp(18), paint)
        paint.typeface = Typeface.create("sans", Typeface.NORMAL)
        paint.textSize = sp(10)
        paint.color = Color.rgb(152, 171, 199)
        val backendName = when (config.backend) {
            "vulkan" -> "VULKAN"
            "opengl" -> "OPENGL ES"
            else -> "AUTO GPU"
        }
        canvas.drawText("$backendName  ·  ${config.targetFps} FPS  ·  ${config.quality.uppercase()}", pad + dp(52), pad + sp(35), paint)

        drawBackButton(canvas, pad, pad)

        val right = width - pad
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = sp(13)
        paint.color = Color.WHITE
        canvas.drawText("得分 $score", right, pad + sp(16), paint)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - gameStartedAt) / 1000L).coerceAtLeast(0L)
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
            renderedFrames = 0
            fpsWindowStartNanos = now
        }
    }

    private fun configurePalette() {
        when (config.mapId) {
            "wilds" -> {
                mapPrimary = Color.rgb(185, 255, 107)
                mapSecondary = Color.rgb(255, 114, 199)
            }
            "lab" -> {
                mapPrimary = Color.rgb(255, 207, 74)
                mapSecondary = Color.rgb(255, 90, 119)
            }
        }
        when {
            config.skinId.contains("dragon") -> {
                snakePrimary = Color.rgb(80, 224, 255)
                snakeSecondary = Color.rgb(71, 132, 255)
            }
            config.skinId.contains("golden") -> {
                snakePrimary = Color.rgb(255, 235, 143)
                snakeSecondary = Color.rgb(255, 160, 42)
            }
            config.skinId.contains("emerald") || config.skinId.contains("prism") -> {
                snakePrimary = Color.rgb(158, 255, 114)
                snakeSecondary = Color.rgb(68, 211, 166)
            }
            config.skinId.contains("acid") || config.skinId.contains("spore") -> {
                snakePrimary = Color.rgb(195, 255, 63)
                snakeSecondary = Color.rgb(142, 73, 255)
            }
            config.skinId.contains("red") -> {
                snakePrimary = Color.rgb(255, 100, 89)
                snakeSecondary = Color.rgb(255, 194, 74)
            }
            config.skinId.contains("ghost") || config.skinId.contains("tortoise") -> {
                snakePrimary = Color.rgb(162, 122, 255)
                snakeSecondary = Color.rgb(49, 75, 120)
            }
        }
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
}
