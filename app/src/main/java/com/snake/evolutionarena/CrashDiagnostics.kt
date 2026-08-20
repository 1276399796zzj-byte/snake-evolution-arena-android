package com.snake.evolutionarena

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

class ArenaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}

/** Stores a compact, user-shareable report when a Java/Kotlin crash reaches the process handler. */
internal object CrashDiagnostics {
    private const val PREFERENCES = "arena_diagnostics"
    private const val CURRENT_STAGE = "current_stage"
    private const val LAST_FAILURE = "last_failure"
    private const val MAX_TRACE_LENGTH = 6_000

    @Volatile
    private var stage = "application_start"

    @Volatile
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        stage = preferences.getString(CURRENT_STAGE, stage) ?: stage
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordFailure(application, stage, throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun markStage(context: Context, value: String) {
        stage = value
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT_STAGE, value)
            .apply()
    }

    fun recordFailure(context: Context, failureStage: String, throwable: Throwable) {
        val trace = Log.getStackTraceString(throwable).take(MAX_TRACE_LENGTH)
        val signature = "$failureStage:${throwable.javaClass.name}:${throwable.message}"
        val code = signature.hashCode().toUInt().toString(16).uppercase().padStart(8, '0')
        val report = buildString {
            append("诊断码：SEA-")
            append(code)
            append("\n阶段：")
            append(failureStage)
            append("\n异常：")
            append(throwable.javaClass.simpleName)
            throwable.message?.takeIf { it.isNotBlank() }?.let {
                append(" — ")
                append(it)
            }
            append("\n设备：")
            append(Build.MANUFACTURER)
            append(' ')
            append(Build.MODEL)
            append("\n系统：Android ")
            append(Build.VERSION.RELEASE)
            append(" / API ")
            append(Build.VERSION.SDK_INT)
            append("\nABI：")
            append(Build.SUPPORTED_ABIS.joinToString())
            append("\n\n")
            append(trace)
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT_STAGE, failureStage)
            .putString(LAST_FAILURE, report)
            .commit()
    }

    fun consumeLastFailure(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val value = preferences.getString(LAST_FAILURE, null) ?: return null
        preferences.edit().remove(LAST_FAILURE).apply()
        return value
    }
}
