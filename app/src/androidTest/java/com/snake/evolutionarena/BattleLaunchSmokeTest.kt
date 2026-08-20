package com.snake.evolutionarena

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BattleLaunchSmokeTest {
    @Test
    fun compatBattleRemainsAliveOnAndroid16() {
        assertTrue("This smoke test must run on Android 16", Build.VERSION.SDK_INT >= 36)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, BattleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BattleActivity.EXTRA_MAP, "neon")
            putExtra(BattleActivity.EXTRA_MAP_NAME, "霓虹竞技场")
            putExtra(BattleActivity.EXTRA_MODE, "blitz")
            putExtra(BattleActivity.EXTRA_MODE_NAME, "闪击")
            putExtra(BattleActivity.EXTRA_ARCHETYPE, "viper")
            putExtra(BattleActivity.EXTRA_SKIN, "neon-pulse")
            putExtra(BattleActivity.EXTRA_AI_STRENGTH, "veteran")
            putExtra(BattleActivity.EXTRA_BACKEND, "compat")
            putExtra(BattleActivity.EXTRA_TARGET_FPS, 120)
            putExtra(BattleActivity.EXTRA_QUALITY, "balanced")
            putExtra(BattleActivity.EXTRA_EFFECTS, "standard")
            putExtra(BattleActivity.EXTRA_JOYSTICK, "floating")
            putExtra(BattleActivity.EXTRA_HAPTICS, true)
            putExtra(BattleActivity.EXTRA_MUSIC, false)
        }

        ActivityScenario.launch<BattleActivity>(intent).use { scenario ->
            SystemClock.sleep(5_000L)
            scenario.onActivity { activity ->
                assertFalse("BattleActivity unexpectedly finished", activity.isFinishing)
                assertFalse("BattleActivity unexpectedly destroyed", activity.isDestroyed)
                assertTrue(
                    "Battle content was not attached",
                    activity.findViewById<android.view.ViewGroup>(android.R.id.content).childCount > 0,
                )
            }
        }
    }
}
