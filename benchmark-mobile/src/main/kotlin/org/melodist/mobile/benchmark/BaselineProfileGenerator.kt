package org.melodist.mobile.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        val targetPackageName =
            InstrumentationRegistry.getArguments().getString("targetPackage")
                ?: BuildConfig.TARGET_PACKAGE

        baselineProfileRule.collect(
            packageName = targetPackageName,
            maxIterations = 3,
            strictStability = false,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            // 保持前台停留，由外部手势注入器驱动列表滑动
            Thread.sleep(3000L)
        }
    }
}
