package com.snake.evolutionarena

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import kotlin.math.sin

class MainActivity : Activity() {
    private val backgroundColor = Color.rgb(5, 9, 21)
    private val surfaceColor = Color.rgb(12, 21, 41)
    private val cyan = Color.rgb(82, 246, 255)
    private val violet = Color.rgb(155, 92, 255)
    private val primaryText = Color.rgb(245, 250, 255)
    private val mutedText = Color.rgb(145, 161, 189)
    private val borderColor = Color.rgb(42, 58, 88)

    private lateinit var catalog: GameCatalog
    private lateinit var selectedMap: ArenaMap
    private lateinit var selectedMode: GameMode
    private lateinit var selectedArchetype: Archetype
    private lateinit var selectedSkin: Skin

    private var aiStrength = "veteran"
    private var backend = "auto"
    private var targetFps = "120"
    private var quality = "balanced"
    private var effects = "standard"
    private var joystick = "floating"
    private var leftHanded = false
    private var haptics = true
    private var music = true

    private val mapCards = mutableMapOf<String, SelectableCard>()
    private val archetypeCards = mutableMapOf<String, SelectableCard>()
    private val skinButtons = mutableMapOf<String, TextView>()
    private lateinit var missionSummary: TextView
    private lateinit var launchSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        catalog = GameCatalog.load(this)
        val preferences = getSharedPreferences("arena_settings", MODE_PRIVATE)
        selectedMap = catalog.maps.firstOrNull { it.id == preferences.getString("map", "neon") }
            ?: catalog.maps.first()
        selectedMode = catalog.modes.firstOrNull { it.id == preferences.getString("mode", "blitz") }
            ?: catalog.modes.first()
        selectedArchetype = catalog.archetypes.firstOrNull {
            it.id == preferences.getString("archetype", "viper")
        } ?: catalog.archetypes.first()
        selectedSkin = catalog.skins.firstOrNull {
            it.id == preferences.getString("skin", "neon-pulse")
        } ?: catalog.skins.first()
        aiStrength = preferences.getString("ai", "veteran") ?: "veteran"
        backend = preferences.getString("backend", "auto") ?: "auto"
        targetFps = preferences.getString("fps", "120") ?: "120"
        quality = preferences.getString("quality", "balanced") ?: "balanced"
        effects = preferences.getString("effects", "standard") ?: "standard"
        joystick = preferences.getString("joystick", "floating") ?: "floating"
        leftHanded = preferences.getBoolean("left_handed", false)
        haptics = preferences.getBoolean("haptics", true)
        music = preferences.getBoolean("music", true)

        setContentView(buildLobby())
    }

    private fun buildLobby(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        root.addView(ArenaBackdropView(this), matchParent())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }
        scroll.addView(content, matchWrap())
        root.addView(scroll, matchParent())

        content.addView(buildHeader())
        content.addView(space(30))
        content.addView(label("SEASON 01   —   独行者协议", 12f, cyan, Typeface.BOLD).apply {
            letterSpacing = 0.13f
        })
        content.addView(space(12))
        content.addView(label("吞噬。进化。", 38f, primaryText, Typeface.BOLD))
        content.addView(label("重塑猎场。", 34f, Color.TRANSPARENT, Typeface.BOLD).apply {
            setTextColor(cyan)
            alpha = 0.82f
        })
        content.addView(space(10))
        content.addView(label("选定地图与战斗规则，组装你的战蛇。大厅保持竖屏，开始后将直接进入沉浸式横屏战场。", 15f, mutedText).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        content.addView(space(24))

        content.addView(section("01", "选择作战区域", "3 张完整地图"))
        catalog.maps.forEach { map ->
            content.addView(buildMapCard(map), LinearLayout.LayoutParams(-1, dp(176)).apply {
                bottomMargin = dp(12)
            })
        }

        content.addView(space(14))
        content.addView(section("02", "作战模式", "独立规则与音轨"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catalog.modes.forEachIndexed { index, mode ->
            modeRow.addView(
                selectorButton(
                    title = mode.shortName,
                    subtitle = when (mode.id) {
                        "blitz" -> "3 MIN"
                        "expedition" -> "10 MIN"
                        else -> "∞"
                    },
                    selected = selectedMode.id == mode.id,
                ) {
                    selectedMode = mode
                    rebuildSelectorRow(modeRow, catalog.modes.map { it.id }, mode.id)
                    updateMissionSummary()
                },
                LinearLayout.LayoutParams(0, dp(74), 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                },
            )
        }
        content.addView(modeRow)
        missionSummary = label("", 13f, mutedText).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(missionSummary)
        updateMissionSummary()

        content.addView(space(24))
        content.addView(section("03", "AI 强度", "离线对手"))
        content.addView(
            segmented(
                listOf("新锐" to "rookie", "老练" to "veteran", "梦魇" to "nightmare"),
                aiStrength,
            ) { aiStrength = it },
        )

        content.addView(space(24))
        content.addView(section("04", "选择原型", "4 种构筑起点"))
        val archetypeGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalog.archetypes.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { columnIndex, archetype ->
                row.addView(
                    buildArchetypeCard(archetype),
                    LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                        if (columnIndex > 0) leftMargin = dp(8)
                    },
                )
            }
            archetypeGrid.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (rowIndex > 0) topMargin = dp(8)
            })
        }
        content.addView(archetypeGrid)

        content.addView(space(24))
        content.addView(section("05", "外观实验室", "12 款原创皮肤"))
        content.addView(buildSkinGrid())

        content.addView(space(24))
        content.addView(section("06", "画面与操作", "保存到本机"))
        val settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = rounded(surfaceColor, dp(18).toFloat(), borderColor)
        }
        settingsPanel.addView(settingTitle("图形接口", "自动模式会优先 Vulkan，不兼容时回退 OpenGL ES"))
        settingsPanel.addView(segmented(listOf("自动" to "auto", "Vulkan" to "vulkan", "OpenGL" to "opengl"), backend) { backend = it })
        settingsPanel.addView(settingGap())
        settingsPanel.addView(settingTitle("目标帧率", "120 FPS 仅在屏幕与系统允许时启用"))
        settingsPanel.addView(segmented(listOf("30" to "30", "45" to "45", "60" to "60", "90" to "90", "120" to "120"), targetFps) { targetFps = it })
        settingsPanel.addView(settingGap())
        settingsPanel.addView(settingTitle("画面质量", "性能档会降低动态背景和粒子数量"))
        settingsPanel.addView(segmented(listOf("性能" to "performance", "均衡" to "balanced", "高清" to "high"), quality) { quality = it })
        settingsPanel.addView(settingGap())
        settingsPanel.addView(settingTitle("技能特效", "精简 / 标准 / 华丽"))
        settingsPanel.addView(segmented(listOf("精简" to "compact", "标准" to "standard", "华丽" to "luxury"), effects) { effects = it })
        settingsPanel.addView(settingGap())
        settingsPanel.addView(settingTitle("摇杆方式", "浮动摇杆会在首次按下位置出现"))
        settingsPanel.addView(segmented(listOf("固定" to "fixed", "浮动" to "floating"), joystick) { joystick = it })
        settingsPanel.addView(settingGap())
        settingsPanel.addView(toggleRow("左手布局", "摇杆与技能区左右互换", leftHanded) { leftHanded = it })
        settingsPanel.addView(toggleRow("触感反馈", "冲刺、技能命中与升级反馈", haptics) { haptics = it })
        settingsPanel.addView(toggleRow("模式音乐", "每种模式使用独立动态音轨", music) { music = it })
        content.addView(settingsPanel)

        content.addView(space(20))
        content.addView(buildLaunchButton(), LinearLayout.LayoutParams(-1, dp(76)))
        content.addView(label("C++ 60Hz 固定逻辑 · ARM64 原生构建 · 设置可随时调整", 11f, mutedText).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        return root
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val mark = FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(Color.argb(60, 82, 246, 255), Color.argb(30, 155, 92, 255))
                    setStroke(dp(1), Color.argb(150, 82, 246, 255))
                }
                addView(label("●", 26f, cyan, Typeface.BOLD).apply { gravity = Gravity.CENTER }, matchParent())
            }
            addView(mark, LinearLayout.LayoutParams(dp(54), dp(54)))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label("蛇域进化", 22f, primaryText, Typeface.BOLD))
                addView(label("EVOLUTION ARENA", 9f, cyan, Typeface.BOLD).apply { letterSpacing = 0.22f })
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(label("AI 战场\n已就绪", 11f, primaryText, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(Color.argb(115, 16, 39, 56), dp(18).toFloat(), Color.argb(120, 82, 246, 255))
            })
        }
    }

    private fun buildMapCard(map: ArenaMap): View {
        val frame = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = rounded(surfaceColor, dp(18).toFloat())
            isClickable = true
            isFocusable = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(assetDrawable(map.preview))
            contentDescription = "${map.name}地图预览"
        }
        frame.addView(image, matchParent())
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
                Color.argb(245, 5, 9, 21),
                Color.argb(110, 5, 9, 21),
                Color.argb(10, 5, 9, 21),
            ))
        }, matchParent())

        frame.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(12), dp(50), dp(15))
            addView(label(map.kicker, 10f, Color.parseColor(map.accent), Typeface.BOLD).apply { letterSpacing = 0.12f })
            addView(label(map.name, 22f, primaryText, Typeface.BOLD))
            addView(label("${map.description}  ·  ${map.mechanic}", 12f, Color.rgb(192, 205, 224)))
        }, matchParent())

        val check = label("✓", 16f, backgroundColor, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cyan)
            }
        }
        frame.addView(check, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(12), dp(12), 0)
        })
        val border = View(this).apply { isClickable = false }
        frame.addView(border, matchParent())
        mapCards[map.id] = SelectableCard(frame, border, check)
        frame.setOnClickListener {
            selectedMap = map
            updateMapSelection()
            updateMissionSummary()
        }
        updateMapSelection()
        return frame
    }

    private fun updateMapSelection() {
        mapCards.forEach { (id, card) ->
            val selected = id == selectedMap.id
            card.border.background = rounded(Color.TRANSPARENT, dp(18).toFloat(), if (selected) cyan else Color.argb(90, 42, 58, 88), if (selected) 2 else 1)
            card.badge.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            card.root.animate().scaleX(if (selected) 1f else 0.985f).scaleY(if (selected) 1f else 0.985f).setDuration(160).start()
        }
    }

    private fun buildArchetypeCard(archetype: Archetype): View {
        val card = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
        }
        val border = View(this)
        card.addView(border, matchParent())
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(10))
            addView(label(archetype.name, 18f, primaryText, Typeface.BOLD))
            addView(label(archetype.title, 11f, Color.parseColor(archetype.accent), Typeface.BOLD))
            addView(label(archetype.description, 11f, mutedText).apply { maxLines = 2 })
            addView(label(archetype.perk, 10f, Color.rgb(196, 213, 233), Typeface.BOLD))
        }
        card.addView(body, matchParent())
        val badge = label("✓", 12f, backgroundColor, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cyan) }
        }
        card.addView(badge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(9), dp(9), 0)
        })
        archetypeCards[archetype.id] = SelectableCard(card, border, badge)
        card.setOnClickListener {
            selectedArchetype = archetype
            updateArchetypeSelection()
        }
        updateArchetypeSelection()
        return card
    }

    private fun updateArchetypeSelection() {
        archetypeCards.forEach { (id, card) ->
            val selected = id == selectedArchetype.id
            val accent = catalog.archetypes.firstOrNull { it.id == id }?.accent?.let(Color::parseColor) ?: cyan
            card.border.background = rounded(
                if (selected) Color.argb(175, 13, 35, 51) else Color.argb(205, 12, 21, 41),
                dp(16).toFloat(),
                if (selected) accent else borderColor,
                if (selected) 2 else 1,
            )
            card.badge.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun buildSkinGrid(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.argb(205, 12, 21, 41), dp(18).toFloat(), borderColor)
        }
        catalog.skins.chunked(3).forEachIndexed { rowIndex, skins ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            skins.forEachIndexed { columnIndex, skin ->
                val button = label(skin.name, 11f, primaryText, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedSkin = skin
                        updateSkinSelection()
                    }
                }
                skinButtons[skin.id] = button
                row.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    if (columnIndex > 0) leftMargin = dp(7)
                })
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (rowIndex > 0) topMargin = dp(7)
            })
        }
        updateSkinSelection()
        return panel
    }

    private fun updateSkinSelection() {
        skinButtons.forEach { (id, button) ->
            val selected = id == selectedSkin.id
            button.background = rounded(
                if (selected) Color.argb(95, 82, 246, 255) else Color.argb(125, 20, 31, 54),
                dp(13).toFloat(),
                if (selected) cyan else Color.argb(120, 42, 58, 88),
                if (selected) 2 else 1,
            )
            button.setTextColor(if (selected) cyan else primaryText)
        }
    }

    private fun buildLaunchButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(14), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(17, 75, 89), Color.rgb(52, 36, 92))).apply {
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), cyan)
                },
                null,
            )
            isClickable = true
            isFocusable = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("当前任务", 11f, mutedText))
                addView(label("${selectedMap.name} · ${selectedMode.shortName}", 15f, primaryText, Typeface.BOLD).also {
                    launchSummary = it
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("进入战场  ➜", 20f, cyan, Typeface.BOLD))
            setOnClickListener { launchBattle() }
        }
    }

    private fun launchBattle() {
        getSharedPreferences("arena_settings", MODE_PRIVATE).edit()
            .putString("map", selectedMap.id)
            .putString("mode", selectedMode.id)
            .putString("archetype", selectedArchetype.id)
            .putString("skin", selectedSkin.id)
            .putString("ai", aiStrength)
            .putString("backend", backend)
            .putString("fps", targetFps)
            .putString("quality", quality)
            .putString("effects", effects)
            .putString("joystick", joystick)
            .putBoolean("left_handed", leftHanded)
            .putBoolean("haptics", haptics)
            .putBoolean("music", music)
            .apply()

        startActivity(Intent(this, BattleActivity::class.java).apply {
            putExtra(BattleActivity.EXTRA_MAP, selectedMap.id)
            putExtra(BattleActivity.EXTRA_MAP_NAME, selectedMap.name)
            putExtra(BattleActivity.EXTRA_MODE, selectedMode.id)
            putExtra(BattleActivity.EXTRA_MODE_NAME, selectedMode.shortName)
            putExtra(BattleActivity.EXTRA_ARCHETYPE, selectedArchetype.id)
            putExtra(BattleActivity.EXTRA_SKIN, selectedSkin.id)
            putExtra(BattleActivity.EXTRA_BACKEND, backend)
            putExtra(BattleActivity.EXTRA_TARGET_FPS, targetFps.toIntOrNull() ?: 60)
            putExtra(BattleActivity.EXTRA_QUALITY, quality)
            putExtra(BattleActivity.EXTRA_EFFECTS, effects)
            putExtra(BattleActivity.EXTRA_JOYSTICK, joystick)
            putExtra(BattleActivity.EXTRA_LEFT_HANDED, leftHanded)
            putExtra(BattleActivity.EXTRA_HAPTICS, haptics)
            putExtra(BattleActivity.EXTRA_MUSIC, music)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun updateMissionSummary() {
        if (!::missionSummary.isInitialized) return
        missionSummary.text = "${selectedMode.description}\n目标：${selectedMode.objective}  ·  音轨：${selectedMode.soundtrack} ${selectedMode.bpm} BPM\n地图机制：${selectedMap.mechanic}"
        if (::launchSummary.isInitialized) launchSummary.text = "${selectedMap.name} · ${selectedMode.shortName}"
    }

    private fun rebuildSelectorRow(row: LinearLayout, ids: List<String>, selected: String) {
        for (index in 0 until row.childCount) {
            val button = row.getChildAt(index) as? TextView ?: continue
            applySelectorStyle(button, ids.getOrNull(index) == selected)
        }
    }

    private fun selectorButton(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit): TextView =
        label("$title\n$subtitle", 14f, primaryText, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 0.86f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            applySelectorStyle(this, selected)
        }

    private fun applySelectorStyle(button: TextView, selected: Boolean) {
        button.background = rounded(
            if (selected) Color.argb(105, 82, 246, 255) else Color.argb(185, 12, 21, 41),
            dp(15).toFloat(),
            if (selected) cyan else borderColor,
            if (selected) 2 else 1,
        )
        button.setTextColor(if (selected) cyan else primaryText)
    }

    private fun segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.rgb(7, 13, 27), dp(14).toFloat(), Color.argb(110, 42, 58, 88))
        }
        fun redraw(value: String) {
            options.forEachIndexed { index, pair ->
                val child = row.getChildAt(index) as TextView
                val active = pair.second == value
                child.background = rounded(if (active) Color.rgb(21, 64, 77) else Color.TRANSPARENT, dp(10).toFloat())
                child.setTextColor(if (active) cyan else mutedText)
            }
        }
        options.forEach { option ->
            row.addView(label(option.first, 12f, mutedText, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onSelect(option.second)
                    redraw(option.second)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        redraw(selected)
        return row
    }

    private fun toggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(8), dp(1), dp(3))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 14f, primaryText, Typeface.BOLD))
                addView(label(description, 11f, mutedText))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = checked
                buttonTintList = ColorStateList.valueOf(cyan)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
        }
    }

    private fun section(number: String, title: String, helper: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(label(number, 11f, cyan, Typeface.BOLD).apply { letterSpacing = 0.1f })
            addView(label(title, 19f, primaryText, Typeface.BOLD).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(helper, 10f, mutedText))
        }

    private fun settingTitle(title: String, helper: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 14f, primaryText, Typeface.BOLD))
            addView(label(helper, 10f, mutedText).apply { setPadding(0, dp(2), 0, dp(8)) })
        }

    private fun settingGap(): View = View(this).apply {
        setBackgroundColor(Color.argb(75, 70, 87, 115))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(16), 0, dp(16)) }
    }

    private fun label(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create("sans", style)
            includeFontPadding = false
        }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            stroke?.let { setStroke(dp(strokeWidth), it) }
        }

    private fun assetDrawable(path: String): Drawable? = assets.open(path).use { Drawable.createFromStream(it, path) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun space(height: Int): Space = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun matchParent() = ViewGroup.LayoutParams(-1, -1)
    private fun matchWrap() = ViewGroup.LayoutParams(-1, -2)

    private data class SelectableCard(val root: View, val border: View, val badge: View)
}

private class ArenaBackdropView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var background: LinearGradient? = null
    private val startedAt = SystemClock.elapsedRealtime()
    private val points = floatArrayOf(
        .07f, .13f, .42f, .21f, .78f, .11f, .92f, .34f,
        .16f, .49f, .62f, .43f, .84f, .58f, .28f, .71f,
        .51f, .82f, .93f, .76f, .11f, .91f, .71f, .94f,
    )

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        background = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(4, 18, 29), Color.rgb(8, 10, 31), Color.rgb(18, 8, 36)),
            floatArrayOf(0f, .48f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        for (index in points.indices step 2) {
            val phase = index * .63f
            val x = points[index] * width + sin(seconds * .18f + phase) * width * .025f
            val y = points[index + 1] * height + sin(seconds * .13f + phase * .7f) * height * .018f
            paint.color = if (index % 4 == 0) Color.argb(18, 82, 246, 255) else Color.argb(15, 155, 92, 255)
            canvas.drawCircle(x, y, 12f + (index % 5) * 6f, paint)
        }
        postInvalidateDelayed(50L)
    }
}
