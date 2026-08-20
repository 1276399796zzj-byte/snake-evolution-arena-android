package com.snake.evolutionarena

import android.graphics.Color

data class ArenaPalette(
    val mapPrimary: Int,
    val mapSecondary: Int,
    val snakePrimary: Int,
    val snakeSecondary: Int,
    val clearColor: Int,
)

fun arenaPalette(config: BattleConfig): ArenaPalette {
    val mapColors = when (config.mapId) {
        "wilds" -> Color.rgb(185, 255, 107) to Color.rgb(255, 114, 199)
        "lab" -> Color.rgb(255, 207, 74) to Color.rgb(255, 90, 119)
        else -> Color.rgb(82, 246, 255) to Color.rgb(155, 92, 255)
    }
    val snakeColors = when {
        config.skinId.contains("dragon") -> Color.rgb(80, 224, 255) to Color.rgb(71, 132, 255)
        config.skinId.contains("golden") -> Color.rgb(255, 235, 143) to Color.rgb(255, 160, 42)
        config.skinId.contains("emerald") || config.skinId.contains("prism") ->
            Color.rgb(158, 255, 114) to Color.rgb(68, 211, 166)
        config.skinId.contains("acid") || config.skinId.contains("spore") ->
            Color.rgb(195, 255, 63) to Color.rgb(142, 73, 255)
        config.skinId.contains("red") -> Color.rgb(255, 100, 89) to Color.rgb(255, 194, 74)
        config.skinId.contains("ghost") || config.skinId.contains("tortoise") ->
            Color.rgb(162, 122, 255) to Color.rgb(49, 75, 120)
        else -> Color.rgb(82, 246, 255) to Color.rgb(155, 92, 255)
    }
    val clear = when (config.mapId) {
        "wilds" -> Color.rgb(4, 14, 16)
        "lab" -> Color.rgb(15, 8, 15)
        else -> Color.rgb(3, 8, 18)
    }
    return ArenaPalette(mapColors.first, mapColors.second, snakeColors.first, snakeColors.second, clear)
}
