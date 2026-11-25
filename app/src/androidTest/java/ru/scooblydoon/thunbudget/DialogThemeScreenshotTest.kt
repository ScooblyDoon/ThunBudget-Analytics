package ru.scooblydoon.thunbudget

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogThemeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun darkDialogSurfaceIsGraphiteNotWhite() {
        assertDialogSurface(
            dark = true,
            expected = android.graphics.Color.rgb(0x21, 0x22, 0x2D),
            forbidden = android.graphics.Color.WHITE
        )
    }

    @Test
    fun lightDialogSurfaceIsWhite() {
        assertDialogSurface(
            dark = false,
            expected = android.graphics.Color.WHITE,
            forbidden = android.graphics.Color.rgb(0x21, 0x22, 0x2D)
        )
    }

    private fun assertDialogSurface(dark: Boolean, expected: Int, forbidden: Int) {
        // ComposeTestRule performs setContent on the Activity's main/UI thread.
        // Calling activity.setContent directly from the instrumentation test thread
        // throws CalledFromWrongThreadException before the dialog is even rendered.
        composeRule.setContent {
            val scheme = if (dark) {
                darkColorScheme(background = Color(0xFF090A10), surface = Color(0xFF171821))
            } else {
                lightColorScheme(background = Color(0xFF101010), surface = Color.White)
            }
            ThunBudgetTheme(colorScheme = scheme, shapes = Shapes()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101010)))
                AppAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Проверка темы окна") },
                    text = { Text("Проверка фактического цвета поверхности") },
                    confirmButton = { Button(onClick = {}) { Text("OK") } }
                )
            }
        }

        composeRule.waitForIdle()
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Не удалось получить скриншот окна")
        try {
            val expectedPixels = countNear(bitmap, expected)
            val forbiddenPixels = countNear(bitmap, forbidden)
            assertTrue(
                "Ожидаемый цвет " + Integer.toHexString(expected) + " найден только в " + expectedPixels +
                    " пикселях; контрольный цвет " + Integer.toHexString(forbidden) + " найден в " + forbiddenPixels,
                expectedPixels > 5_000
            )
            assertTrue(
                "В окне обнаружено слишком много пикселей контрольного цвета " +
                    Integer.toHexString(forbidden) + ": " + forbiddenPixels,
                forbiddenPixels < 5_000
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun countNear(bitmap: Bitmap, color: Int): Int {
        val targetR = android.graphics.Color.red(color)
        val targetG = android.graphics.Color.green(color)
        val targetB = android.graphics.Color.blue(color)
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(pixel) - targetR) <= 2 &&
                    kotlin.math.abs(android.graphics.Color.green(pixel) - targetG) <= 2 &&
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - targetB) <= 2
                ) count++
            }
        }
        return count
    }
}