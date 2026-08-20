package com.snake.evolutionarena

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs

class BattleActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var battleView: BattleView
    private lateinit var battleConfig: BattleConfig
    private var sceneRenderer: ArenaSceneRenderer? = null
    private var soundtrack: ProceduralSoundtrack? = null
    private var hostResumed = false
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        keepImmersive()

        val targetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 60).coerceIn(30, 120)
        runCatching { requestBestRefreshRate(targetFps) }

        battleConfig = BattleConfig(
            mapId = intent.getStringExtra(EXTRA_MAP) ?: "neon",
            mapName = intent.getStringExtra(EXTRA_MAP_NAME) ?: "霓虹竞技场",
            modeId = intent.getStringExtra(EXTRA_MODE) ?: "blitz",
            modeName = intent.getStringExtra(EXTRA_MODE_NAME) ?: "闪击",
            archetypeId = intent.getStringExtra(EXTRA_ARCHETYPE) ?: "viper",
            skinId = intent.getStringExtra(EXTRA_SKIN) ?: "neon-pulse",
            aiStrength = intent.getStringExtra(EXTRA_AI_STRENGTH) ?: "veteran",
            backend = intent.getStringExtra(EXTRA_BACKEND) ?: "opengl",
            targetFps = targetFps,
            quality = intent.getStringExtra(EXTRA_QUALITY) ?: "balanced",
            effects = intent.getStringExtra(EXTRA_EFFECTS) ?: "standard",
            floatingJoystick = intent.getStringExtra(EXTRA_JOYSTICK) != "fixed",
            leftHanded = intent.getBooleanExtra(EXTRA_LEFT_HANDED, false),
            haptics = intent.getBooleanExtra(EXTRA_HAPTICS, true),
            music = intent.getBooleanExtra(EXTRA_MUSIC, true),
        )
        root = FrameLayout(this)
        battleView = BattleView(
            context = this,
            config = battleConfig,
            onExit = { finishAfterTransition() },
        )
        installInitialRenderer()
        root.addView(
            battleView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        runCatching { registerPerformanceSignals() }
        if (battleConfig.music) soundtrack = runCatching {
            ProceduralSoundtrack(battleConfig.modeId)
        }.getOrNull()
        root.postDelayed({ markBattleBootSuccessful() }, BATTLE_BOOT_GUARD_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) keepImmersive()
    }

    override fun onResume() {
        super.onResume()
        hostResumed = true
        keepImmersive()
        updateSystemPerformanceTier(powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)
        sceneRenderer?.onHostResume()
        if (::battleView.isInitialized) battleView.resumeGame()
        soundtrack?.play()
    }

    override fun onPause() {
        hostResumed = false
        if (::battleView.isInitialized) battleView.pauseGame()
        sceneRenderer?.onHostPause()
        soundtrack?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        thermalListener?.let { listener ->
            runCatching { powerManager?.removeThermalStatusListener(listener) }
        }
        thermalListener = null
        powerManager = null
        if (::battleView.isInitialized) battleView.releaseGame()
        sceneRenderer?.release()
        sceneRenderer = null
        soundtrack?.release()
        soundtrack = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun keepImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun requestBestRefreshRate(targetFps: Int) {
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return
        val modes = activeDisplay.supportedModes
        val best = modes.minByOrNull { mode ->
            val ratePenalty = abs(mode.refreshRate - targetFps.toFloat())
            val resolutionPenalty = if (mode.physicalWidth == activeDisplay.mode.physicalWidth &&
                mode.physicalHeight == activeDisplay.mode.physicalHeight
            ) 0f else 1000f
            ratePenalty + resolutionPenalty
        } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = targetFps.toFloat()
        }
    }

    private fun installInitialRenderer() {
        val requested = battleConfig.backend
        if (requested == "vulkan" && VulkanBridge.supportLevel() > 0) {
            val installed = runCatching {
                installRenderer(
                    VulkanArenaView(
                        this,
                        battleConfig,
                        VulkanBridge.deviceName(),
                        ::handleRendererFailure,
                    ),
                )
            }.isSuccess
            if (installed) return
        }
        val note = if (requested == "vulkan") "OPENGL ES 3 · VK不可用" else null
        val installed = runCatching {
            installRenderer(
                OpenGlArenaView(this, battleConfig, ::handleRendererFailure),
                note ?: if (requested == "auto") "OPENGL ES 3 · 稳定自动" else null,
            )
        }.isSuccess
        if (!installed) {
            sceneRenderer = null
            battleView.attachSceneRenderer(null, "CANVAS · 安全模式")
        }
    }

    private fun registerPerformanceSignals() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        powerManager = manager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            updateSystemPerformanceTier(status)
        }
        thermalListener = listener
        manager.addThermalStatusListener(mainExecutor, listener)
        updateSystemPerformanceTier(manager.currentThermalStatus)
    }

    private fun updateSystemPerformanceTier(thermalStatus: Int) {
        if (!::battleView.isInitialized) return
        val thermalTier = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        val powerTier = if (powerManager?.isPowerSaveMode == true) 1 else 0
        battleView.setSystemPerformanceTier(maxOf(thermalTier, powerTier))
    }

    private fun installRenderer(renderer: ArenaSceneRenderer, labelOverride: String? = null) {
        val previous = sceneRenderer
        if (previous != null) {
            previous.onHostPause()
            root.removeView(previous.view)
            previous.release()
        }
        sceneRenderer = renderer
        root.addView(
            renderer.view,
            0,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        battleView.attachSceneRenderer(renderer, labelOverride ?: renderer.backendLabel)
        if (hostResumed) renderer.onHostResume()
    }

    private fun handleRendererFailure(reason: String) {
        if (isFinishing || isDestroyed) return
        if (sceneRenderer is VulkanArenaView) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            val installed = runCatching {
                installRenderer(
                    OpenGlArenaView(this, battleConfig, ::handleRendererFailure),
                    "OPENGL ES 3 · 自动回退",
                )
            }.isSuccess
            if (!installed) useCanvasRenderer("CANVAS · 安全回退")
        } else {
            Toast.makeText(this, "$reason，已启用兼容渲染", Toast.LENGTH_SHORT).show()
            useCanvasRenderer("CANVAS · 兼容模式")
        }
    }

    private fun useCanvasRenderer(label: String) {
        val previous = sceneRenderer
        runCatching { previous?.onHostPause() }
        if (previous != null) runCatching { root.removeView(previous.view) }
        runCatching { previous?.release() }
        sceneRenderer = null
        battleView.attachSceneRenderer(null, label)
    }

    private fun markBattleBootSuccessful() {
        if (isFinishing || isDestroyed) return
        getSharedPreferences("arena_settings", MODE_PRIVATE).edit()
            .putBoolean("battle_boot_pending", false)
            .apply()
    }

    companion object {
        const val EXTRA_MAP = "map"
        const val EXTRA_MAP_NAME = "map_name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MODE_NAME = "mode_name"
        const val EXTRA_ARCHETYPE = "archetype"
        const val EXTRA_SKIN = "skin"
        const val EXTRA_AI_STRENGTH = "ai_strength"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_TARGET_FPS = "target_fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_EFFECTS = "effects"
        const val EXTRA_JOYSTICK = "joystick"
        const val EXTRA_LEFT_HANDED = "left_handed"
        const val EXTRA_HAPTICS = "haptics"
        const val EXTRA_MUSIC = "music"
        private const val BATTLE_BOOT_GUARD_MS = 4_000L
    }
}

data class BattleConfig(
    val mapId: String,
    val mapName: String,
    val modeId: String,
    val modeName: String,
    val archetypeId: String,
    val skinId: String,
    val aiStrength: String,
    val backend: String,
    val targetFps: Int,
    val quality: String,
    val effects: String,
    val floatingJoystick: Boolean,
    val leftHanded: Boolean,
    val haptics: Boolean,
    val music: Boolean,
)
