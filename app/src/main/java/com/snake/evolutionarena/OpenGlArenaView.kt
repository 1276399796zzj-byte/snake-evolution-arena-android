package com.snake.evolutionarena

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class OpenGlArenaView(
    context: Context,
    config: BattleConfig,
    private val onFailure: (String) -> Unit,
) : GLSurfaceView(context), ArenaSceneRenderer {
    private val arenaRenderer = OpenGlArenaRenderer(config) { reason ->
        post { onFailure(reason) }
    }
    private val targetFps = config.targetFps.toFloat()

    override val view: View get() = this
    override val backendLabel: String = "OPENGL ES 3"

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(arenaRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun submitFrame(
        snapshot: FloatArray,
        snapshotSize: Int,
        directionX: Float,
        directionY: Float,
        pulseStartedMs: Long,
        shieldStartedMs: Long,
        performanceTier: Int,
        cameraX: Float,
        cameraY: Float,
    ) {
        arenaRenderer.submitFrame(
            snapshot,
            snapshotSize,
            directionX,
            directionY,
            pulseStartedMs,
            shieldStartedMs,
            performanceTier,
            cameraX,
            cameraY,
        )
        requestRender()
    }

    override fun onHostResume() {
        onResume()
        requestRender()
    }

    override fun onHostPause() {
        onPause()
    }

    override fun release() {
        arenaRenderer.release()
    }

    private inner class OpenGlArenaRenderer(
        private val config: BattleConfig,
        private val reportFailure: (String) -> Unit,
    ) : Renderer {
        private val lock = Any()
        private val pendingSnapshot = FloatArray(MAX_SNAPSHOT_FLOATS)
        private val renderSnapshot = FloatArray(MAX_SNAPSHOT_FLOATS)
        private val geometry = SceneGeometryBuilder(config, resources.displayMetrics.density)
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
        private var pendingPerformanceTier = 0
        private var renderPerformanceTier = 0
        private var pendingCameraX = 0f
        private var pendingCameraY = 0f
        private var renderCameraX = 0f
        private var renderCameraY = 0f
        private var viewportWidth = 1
        private var viewportHeight = 1
        private var program = 0
        private var vertexBufferObject = 0
        private var released = false

        fun submitFrame(
            snapshot: FloatArray,
            snapshotSize: Int,
            directionX: Float,
            directionY: Float,
            pulseStartedMs: Long,
            shieldStartedMs: Long,
            performanceTier: Int,
            cameraX: Float,
            cameraY: Float,
        ) {
            val size = snapshotSize.coerceIn(0, minOf(snapshot.size, pendingSnapshot.size))
            synchronized(lock) {
                snapshot.copyInto(pendingSnapshot, endIndex = size)
                pendingSize = size
                pendingDirectionX = directionX
                pendingDirectionY = directionY
                pendingPulseStartedMs = pulseStartedMs
                pendingShieldStartedMs = shieldStartedMs
                pendingPerformanceTier = performanceTier
                pendingCameraX = cameraX
                pendingCameraY = cameraY
            }
        }

        override fun onSurfaceCreated(unused: GL10?, eglConfig: EGLConfig?) {
            if (released) return
            try {
                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                val buffers = IntArray(1)
                GLES30.glGenBuffers(1, buffers, 0)
                vertexBufferObject = buffers[0]
                checkGlError("创建 OpenGL 缓冲区")
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                requestSurfaceFrameRate(holder.surface, targetFps)
            } catch (error: RuntimeException) {
                fail("OpenGL ES 初始化失败：${error.message ?: "未知错误"}")
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }

        override fun onDrawFrame(unused: GL10?) {
            if (released || program == 0 || vertexBufferObject == 0) return
            try {
                synchronized(lock) {
                    pendingSnapshot.copyInto(renderSnapshot, endIndex = pendingSize)
                    renderSize = pendingSize
                    renderDirectionX = pendingDirectionX
                    renderDirectionY = pendingDirectionY
                    renderPulseStartedMs = pendingPulseStartedMs
                    renderShieldStartedMs = pendingShieldStartedMs
                    renderPerformanceTier = pendingPerformanceTier
                    renderCameraX = pendingCameraX
                    renderCameraY = pendingCameraY
                }

                val vertexCount = geometry.build(
                    renderSnapshot,
                    renderSize,
                    viewportWidth,
                    viewportHeight,
                    renderDirectionX,
                    renderDirectionY,
                    renderPulseStartedMs,
                    renderShieldStartedMs,
                    SystemClock.elapsedRealtime(),
                    renderPerformanceTier,
                    renderCameraX,
                    renderCameraY,
                )
                GLES30.glClearColor(geometry.clearRed(), geometry.clearGreen(), geometry.clearBlue(), 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glUseProgram(program)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferObject)
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    geometry.vertices.limit() * Float.SIZE_BYTES,
                    geometry.vertices,
                    GLES30.GL_STREAM_DRAW,
                )

                val stride = SceneGeometryBuilder.BYTES_PER_VERTEX
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * Float.SIZE_BYTES)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 4 * Float.SIZE_BYTES)
                GLES30.glEnableVertexAttribArray(3)
                GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 8 * Float.SIZE_BYTES)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
                checkGlError("绘制 OpenGL 场景")
            } catch (error: RuntimeException) {
                fail("OpenGL ES 渲染失败：${error.message ?: "未知错误"}")
            }
        }

        fun release() {
            released = true
            if (program != 0 || vertexBufferObject != 0) {
                queueEvent {
                    if (vertexBufferObject != 0) {
                        GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferObject), 0)
                        vertexBufferObject = 0
                    }
                    if (program != 0) {
                        GLES30.glDeleteProgram(program)
                        program = 0
                    }
                }
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES30.glCreateProgram()
            GLES30.glAttachShader(result, vertexShader)
            GLES30.glAttachShader(result, fragmentShader)
            GLES30.glLinkProgram(result)
            val status = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            if (status[0] == 0) {
                val message = GLES30.glGetProgramInfoLog(result)
                GLES30.glDeleteProgram(result)
                throw IllegalStateException("着色器链接失败：$message")
            }
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException(message)
            }
            return shader
        }

        private fun checkGlError(operation: String) {
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) throw IllegalStateException("$operation (0x${error.toString(16)})")
        }

        private fun fail(reason: String) {
            if (failureReported.compareAndSet(false, true)) reportFailure(reason)
        }
    }

    companion object {
        private const val MAX_SNAPSHOT_FLOATS = 8192
        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
            layout(location = 3) in float aShape;
            out vec2 vUv;
            out vec4 vColor;
            flat out float vShape;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aUv;
                vColor = aColor;
                vShape = aShape;
            }
        """
        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            in vec4 vColor;
            flat in float vShape;
            out vec4 outColor;
            void main() {
                float coverage = 1.0;
                if (vShape > 1.5) {
                    float radius = length(vUv);
                    coverage = smoothstep(1.0, 0.93, radius) * smoothstep(0.76, 0.84, radius);
                } else if (vShape > 0.5) {
                    coverage = smoothstep(1.0, 0.88, length(vUv));
                }
                if (coverage <= 0.001) discard;
                outColor = vec4(vColor.rgb, vColor.a * coverage);
            }
        """
    }
}

internal fun requestSurfaceFrameRate(surface: Surface, targetFps: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        surface.setFrameRate(
            targetFps,
            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            Surface.CHANGE_FRAME_RATE_ALWAYS,
        )
    } else {
        surface.setFrameRate(targetFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}
