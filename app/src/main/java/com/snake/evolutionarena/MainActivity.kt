package com.snake.evolutionarena

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 9, 21)
        window.navigationBarColor = Color.rgb(5, 9, 21)
        setContentView(buildBootScreen())
    }

    private fun buildBootScreen(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((28 * density).toInt(), 0, (28 * density).toInt(), 0)
            setBackgroundColor(Color.rgb(5, 9, 21))

            addView(text("蛇域进化", 38f, Color.WHITE, Typeface.BOLD))
            addView(text("EVOLUTION ARENA", 13f, Color.rgb(82, 246, 255), Typeface.BOLD).apply {
                letterSpacing = 0.22f
            })
            addView(text("原生战斗引擎初始化完成", 17f, Color.rgb(145, 161, 189), Typeface.NORMAL).apply {
                setPadding(0, (28 * density).toInt(), 0, 0)
            })
            addView(text("Core ${NativeBridge.coreVersion()}  ·  Save v${NativeBridge.saveFormatVersion()}", 13f, Color.rgb(155, 92, 255), Typeface.NORMAL).apply {
                setPadding(0, (10 * density).toInt(), 0, 0)
            })
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans", style)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
