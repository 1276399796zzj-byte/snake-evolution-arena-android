package com.snake.evolutionarena

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.abs

class BattleActivity : Activity() {
    private lateinit var battleView: BattleView
    private var soundtrack: ProceduralSoundtrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        keepImmersive()

        val targetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 60).coerceIn(30, 120)
        requestBestRefreshRate(targetFps)

        val battleConfig = BattleConfig(
            mapId = intent.getStringExtra(EXTRA_MAP) ?: "neon",
            mapName = intent.getStringExtra(EXTRA_MAP_NAME) ?: "霓虹竞技场",
            modeId = intent.getStringExtra(EXTRA_MODE) ?: "blitz",
            modeName = intent.getStringExtra(EXTRA_MODE_NAME) ?: "闪击",
            archetypeId = intent.getStringExtra(EXTRA_ARCHETYPE) ?: "viper",
            skinId = intent.getStringExtra(EXTRA_SKIN) ?: "neon-pulse",
            aiStrength = intent.getStringExtra(EXTRA_AI_STRENGTH) ?: "veteran",
            backend = intent.getStringExtra(EXTRA_BACKEND) ?: "auto",
            targetFps = targetFps,
            quality = intent.getStringExtra(EXTRA_QUALITY) ?: "balanced",
            effects = intent.getStringExtra(EXTRA_EFFECTS) ?: "standard",
            floatingJoystick = intent.getStringExtra(EXTRA_JOYSTICK) != "fixed",
            leftHanded = intent.getBooleanExtra(EXTRA_LEFT_HANDED, false),
            haptics = intent.getBooleanExtra(EXTRA_HAPTICS, true),
            music = intent.getBooleanExtra(EXTRA_MUSIC, true),
        )
        battleView = BattleView(
            context = this,
            config = battleConfig,
            onExit = { finishAfterTransition() },
        )
        setContentView(battleView)
        if (battleConfig.music) soundtrack = ProceduralSoundtrack(battleConfig.modeId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) keepImmersive()
    }

    override fun onResume() {
        super.onResume()
        keepImmersive()
        if (::battleView.isInitialized) battleView.resumeGame()
        soundtrack?.play()
    }

    override fun onPause() {
        if (::battleView.isInitialized) battleView.pauseGame()
        soundtrack?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::battleView.isInitialized) battleView.releaseGame()
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
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
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
