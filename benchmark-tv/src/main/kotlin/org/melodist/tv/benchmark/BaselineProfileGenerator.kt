package org.melodist.tv.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        baselineProfileRule.collect(
            packageName = "org.melodist.tv",
            maxIterations = 5,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wait(Until.hasObject(By.pkg("org.melodist.tv").depth(0)), 5000L)

            repeat(4) {
                device.pressDPadDown()
                Thread.sleep(400)
                device.pressDPadRight()
                Thread.sleep(400)
            }
            repeat(2) {
                device.pressDPadUp()
                Thread.sleep(400)
                device.pressDPadLeft()
                Thread.sleep(400)
            }
        }
    }
}
