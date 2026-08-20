package com.snake.evolutionarena

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

class VulkanArenaView(
    context: Context,
    private val config: BattleConfig,
    deviceName: String,
    private val onFailure: (String) -> Unit,
) : SurfaceView(context), ArenaSceneRenderer, SurfaceHolder.Callback {
    private val renderThread = HandlerThread("SnakeVulkan", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val geometry = SceneGeometryBuilder(config, resources.displayMetrics.density)
    private val lock = Any()
    private val pendingSnapshot = FloatArray(MAX_SNAPSHOT_FLOATS)
    private val renderSnapshot = FloatArray(MAX_SNAPSHOT_FLOATS)
    private val failureReported = AtomicBoolean(false)

    private var pendingSize = 0
    private var renderSize = 0
    private var pendingDirectionX = 1f
    private var pendingDirectionY = 0f
    private var renderDirectionX = 1f
    private var renderDirectionY = 0f
    private var pendingPulseStartedMs = -10_000L
    private var pendingShieldStartedMs = -10_000L
    private var renderPulseStartedMs = -10_000L
    private var renderShieldStartedMs = -10_000L
    private var generation = 0L
    private var renderQueued = false
    private var active = false
    private var released = false

    // Accessed only by renderThread.
    private var nativeHandle = 0L
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    override val view: View get() = this
    override val backendLabel: String = if (deviceName.isBlank()) {
        "VULKAN"
    } else {
        "VULKAN · ${deviceName.take(24)}"
    }

    init {
        setZOrderOnTop(false)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
        requestSurfaceFrameRate(surfaceHolder.surface, config.targetFps.toFloat())
        val surface = surfaceHolder.surface
        val width = width.coerceAtLeast(1)
        val height = height.coerceAtLeast(1)
        renderHandler.post {
            destroyNativeRenderer()
            if (released || !surface.isValid) return@post
            surfaceWidth = width
            surfaceHeight = height
            nativeHandle = NativeBridge.createVulkanRenderer(surface, width, height)
            if (nativeHandle == 0L) {
                fail("Vulkan Surface/Swapchain 初始化失败，已回退 OpenGL ES")
            } else {
                synchronized(lock) { scheduleRenderLocked() }
            }
        }
    }

    override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestSurfaceFrameRate(surfaceHolder.surface, config.targetFps.toFloat())
        renderHandler.post {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            if (nativeHandle != 0L) {
                NativeBridge.resizeVulkanRenderer(nativeHandle, surfaceWidth, surfaceHeight)
            }
        }
    }

    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
        renderHandler.post { destroyNativeRenderer() }
    }

    override fun submitFrame(
        snapshot: FloatArray,
        snapshotSize: Int,
        directionX: Float,
        directionY: Float,
        pulseStartedMs: Long,
        shieldStartedMs: Long,
    ) {
        val size = snapshotSize.coerceIn(0, minOf(snapshot.size, pendingSnapshot.size))
        synchronized(lock) {
            if (released) return
            snapshot.copyInto(pendingSnapshot, endIndex = size)
            pendingSize = size
            pendingDirectionX = directionX
            pendingDirectionY = directionY
            pendingPulseStartedMs = pulseStartedMs
            pendingShieldStartedMs = shieldStartedMs
            generation += 1L
            scheduleRenderLocked()
        }
    }

    override fun onHostResume() {
        synchronized(lock) {
            if (released) return
            active = true
            scheduleRenderLocked()
        }
    }

    override fun onHostPause() {
        synchronized(lock) { active = false }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            active = false
        }
        holder.removeCallback(this)
        renderHandler.post { destroyNativeRenderer() }
        renderThread.quitSafely()
    }

    private fun scheduleRenderLocked() {
        if (released || !active || renderQueued) return
        renderQueued = true
        renderHandler.post { renderLatest() }
    }

    private fun renderLatest() {
        val frameGeneration: Long
        synchronized(lock) {
            if (released || !active) {
                renderQueued = false
                return
            }
            pendingSnapshot.copyInto(renderSnapshot, endIndex = pendingSize)
            renderSize = pendingSize
            renderDirectionX = pendingDirectionX
            renderDirectionY = pendingDirectionY
            renderPulseStartedMs = pendingPulseStartedMs
            renderShieldStartedMs = pendingShieldStartedMs
            frameGeneration = generation
        }

        if (nativeHandle != 0L && renderSize >= SNAPSHOT_HEADER_SIZE) {
            val vertexCount = geometry.build(
                renderSnapshot,
                renderSize,
                surfaceWidth,
                surfaceHeight,
                renderDirectionX,
                renderDirectionY,
                renderPulseStartedMs,
                renderShieldStartedMs,
                SystemClock.elapsedRealtime(),
            )
            val succeeded = NativeBridge.renderVulkan(
                nativeHandle,
                geometry.vertices,
                vertexCount,
                geometry.clearRed(),
                geometry.clearGreen(),
                geometry.clearBlue(),
            )
            if (!succeeded) fail("Vulkan 帧提交失败，已回退 OpenGL ES")
        }

        synchronized(lock) {
            if (!released && active && generation != frameGeneration) {
                renderHandler.post { renderLatest() }
            } else {
                renderQueued = false
            }
        }
    }

    private fun destroyNativeRenderer() {
        if (nativeHandle == 0L) return
        NativeBridge.destroyVulkanRenderer(nativeHandle)
        nativeHandle = 0L
    }

    private fun fail(reason: String) {
        if (failureReported.compareAndSet(false, true)) post { onFailure(reason) }
    }

    companion object {
        private const val MAX_SNAPSHOT_FLOATS = 8192
        private const val SNAPSHOT_HEADER_SIZE = 14
    }
}
