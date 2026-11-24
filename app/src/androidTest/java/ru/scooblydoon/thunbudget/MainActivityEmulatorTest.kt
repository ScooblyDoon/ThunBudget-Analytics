package ru.scooblydoon.thunbudget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class MainActivityEmulatorTest {
    @Test
    fun mainActivityLaunchesOnEmulator() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("MainActivity must create a window", activity.window)
                assertNotNull("MainActivity must create a decor view", activity.window.decorView)
            }
        }
    }
}
