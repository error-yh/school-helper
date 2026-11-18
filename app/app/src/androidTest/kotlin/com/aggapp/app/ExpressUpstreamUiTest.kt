package com.aggapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressUpstreamUiTest {
    @Test(timeout = 45_000) fun threeSectionsAndDetailSheetRenderWithoutRequestingSmsOnEntry() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        // Shell launch avoids OEM blocking instrumentation-origin background starts.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".expresstest"))
        val launch = device.executeShellCommand("am start -W -n ${target.packageName}/com.aggapp.app.ExpressPreviewActivity")
        assertTrue(launch, launch.contains("Status: ok"))
        try {
            assertTrue(device.wait(Until.hasObject(By.text("合成测试包裹")), 10_000))
            assertNotNull(device.findObject(By.text("从短信提取取件码")))
            device.findObject(By.res("express-tab-TRANSIT")).click()
            assertTrue(device.wait(Until.hasObject(By.text("运输中测试包裹")), 5000))
            device.findObject(By.res("express-tab-PICKED")).click()
            assertTrue(device.wait(Until.hasObject(By.text("已签收测试包裹")), 5000))
            device.findObject(By.res("express-tab-WAIT")).click()
            device.wait(Until.hasObject(By.text("合成测试包裹")), 5000)
            device.findObject(By.text("合成测试包裹")).click()
            assertTrue(device.wait(Until.hasObject(By.text("已到达合成驿站")), 5000))
            val screenshot = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "express-upstream-preview.png")
            assertTrue(device.takeScreenshot(screenshot))
        } finally {
            device.pressBack()
        }
    }
}
