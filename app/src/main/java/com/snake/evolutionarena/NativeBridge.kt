package com.snake.evolutionarena

object NativeBridge {
    init {
        System.loadLibrary("snake_engine")
    }

    external fun coreVersion(): String
    external fun saveFormatVersion(): Int
}
