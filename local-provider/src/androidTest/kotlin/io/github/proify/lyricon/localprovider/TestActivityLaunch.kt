package io.github.proify.lyricon.localprovider

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.ui.MainActivity

/** Launch with shell authority on ROMs that block a test process's background activity start. */
internal fun launchTestActivity(): MainActivity {
    val instrument = InstrumentationRegistry.getInstrumentation()
    val monitor = instrument.addMonitor(MainActivity::class.java.name, null, false)
    try {
        val descriptor = instrument.uiAutomation.executeShellCommand(
            "am start -W -n ${instrument.targetContext.packageName}/.ui.MainActivity")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        var activity = monitor.waitForActivityWithTimeout(10_000) as? MainActivity
            ?: error("The system did not open the test activity")
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (true) {
            // A changed appearance can recreate the first instance before it gains focus.
            (monitor.lastActivity as? MainActivity)?.let { activity = it }
            var ready = false
            instrument.runOnMainSync {
                ready = !activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus() && activity.window.decorView.isLaidOut
            }
            if (ready) break
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Activity did not become visible" }
            Thread.sleep(30)
        }
        Thread.sleep(350)
        instrument.waitForIdleSync()
        return activity
    } finally { instrument.removeMonitor(monitor) }
}
