package io.github.proify.lyricon.localprovider

import android.content.Intent
import android.widget.Button
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.data.ProviderStore
import io.github.proify.lyricon.localprovider.data.ProviderSettings
import io.github.proify.lyricon.localprovider.ui.MainActivity
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderPageTest {
    // Verify routes independently from OEM touch injection restrictions. Card geometry and
    // the preview's physical touch interaction are verified by the other device tests.
    private fun activate() = object : androidx.test.espresso.ViewAction {
        override fun getDescription() = "activate visible navigation button"
        override fun getConstraints(): org.hamcrest.Matcher<android.view.View> = isDisplayed()
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            check(view.performClick()) { "Navigation button has no click action" }
            controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun navigationAndRealSettingsPagesOpen() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = launchTestActivity()
        try {
            onView(allOf(withText("设置"), isAssignableFrom(Button::class.java))).perform(activate())
            onView(withText("莫奈取色")).check(matches(isDisplayed()))
            onView(withContentDescription("歌词目录, ${ProviderStore(instrument.targetContext).settings().folders.size} 个目录 · ${ProviderStore(instrument.targetContext).settings().files.size} 份导入歌词")).perform(activate())
            onView(withText("添加歌词目录")).check(matches(isDisplayed()))
            onView(withContentDescription("返回")).perform(activate())
            onView(allOf(withText("歌词库"), isAssignableFrom(Button::class.java))).perform(activate())
            onView(withContentDescription("搜索本地歌词")).check(matches(isDisplayed()))
            onView(allOf(withText("当前"), isAssignableFrom(Button::class.java))).perform(activate())
            onView(allOf(withText("搜索"), isAssignableFrom(Button::class.java))).perform(activate())
            onView(withContentDescription("搜索在线或本地歌词")).check(matches(isDisplayed()))
            onView(withText("筛选")).perform(activate())
            onView(withText("评分规则")).perform(activate())
            onView(withText("字段权重")).check(matches(isDisplayed()))
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
    @Test fun moduleCanReadConfigThroughTheSameApiUsedByHookedPlayers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = context.contentResolver.call(ProviderStore.URI, "config", null, null)!!
        val decoded = ProviderStore.json.decodeFromString<ProviderSettings>(result.getString("json")!!)
        assertEquals(ProviderStore(context).settings(), decoded)
    }
}
