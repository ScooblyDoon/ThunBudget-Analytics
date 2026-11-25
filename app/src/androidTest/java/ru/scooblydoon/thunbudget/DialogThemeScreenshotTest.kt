package ru.scooblydoon.thunbudget

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
            forbidden = android.graphics.Color.WHITE,
            expectedField = android.graphics.Color.rgb(0x34, 0x34, 0x40)
        )
    }

    @Test
    fun lightDialogSurfaceIsWhite() {
        assertDialogSurface(
            dark = false,
            expected = android.graphics.Color.WHITE,
            forbidden = android.graphics.Color.rgb(0x21, 0x22, 0x2D),
            expectedField = android.graphics.Color.rgb(0xF0, 0xF2, 0xF5)
        )
    }

    private fun assertDialogSurface(dark: Boolean, expected: Int, forbidden: Int, expectedField: Int) {
        // ComposeTestRule performs setContent on the Activity's main/UI thread.
        // Calling activity.setContent directly from the instrumentation test thread
        // throws CalledFromWrongThreadException before the dialog is even rendered.
        composeRule.setContent {
            val scheme = if (dark) {
                darkColorScheme(background = Color(0xFF090A10), surface = Color(0xFF171821))
            } else {
                lightColorScheme(background = Color.White, surface = Color.White)
            }
            // Mirror MainActivity: the screen's theme scope ends before modal overlays
            // are emitted, so the overlay host must explicitly re-enter the app theme.
            ThunBudgetTheme(colorScheme = scheme, shapes = Shapes()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101010)))
            }
            ThunBudgetTheme(colorScheme = scheme, shapes = Shapes()) {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Проверка темы окна") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text("Проверка фактического цвета поверхности")
                            TextField(value = "Тестовое поле", onValueChange = {})
                        }
                    },
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
            val fieldPixels = countNear(bitmap, expectedField)
            assertTrue(
                "Ожидаемый цвет " + Integer.toHexString(expected) + " найден только в " + expectedPixels +
                    " пикселях; контрольный цвет " + Integer.toHexString(forbidden) + " найден в " + forbiddenPixels,
                // Surface area depends on emulator density and dialog height; compare against
                // the forbidden color rather than requiring a fixed 5,000 exact-color pixels.
                expectedPixels > 500 && expectedPixels > forbiddenPixels + 1_000
            )
            assertTrue(
                "Ожидаемый цвет поверхности поля " + Integer.toHexString(expectedField) + " найден только в " + fieldPixels + " пикселях",
                fieldPixels > 100
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