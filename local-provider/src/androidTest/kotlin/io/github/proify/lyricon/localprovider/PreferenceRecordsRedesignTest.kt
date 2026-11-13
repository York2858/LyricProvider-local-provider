package io.github.proify.lyricon.localprovider

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.ui.PreferenceRecordsActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreferenceRecordsRedesignTest {
    @Test fun filtersSelectionAndWriteEligibilityStayIndependent() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val prefs=context.getSharedPreferences("playback_preferences",0)
        val old=prefs.getString("records",null)
        val enabled=prefs.getBoolean("enabled",true)
        val records=PreferenceRecordStore(context)
        var activity:android.app.Activity?=null
        try {
            prefs.edit().remove("records").putBoolean("enabled",true).commit()
            records.observe(PreferenceObservation("com.maxmpz.audioplayer","sun","晴天","周杰伦","叶惠美",favorite=true,rating=100,
                file=PreferenceFile(path="/storage/emulated/0/Music/周杰伦/晴天.flac"),observedAt=10000))
            records.observe(PreferenceObservation("ink.trantor.coneplayer","wind","起风了","买辣椒也用券",favorite=true,rating=80,observedAt=9000))
            records.observe(PreferenceObservation("com.maxmpz.audioplayer","sea","花海","周杰伦","魔杰座",favorite=true,rating=100,
                file=PreferenceFile(path="/storage/emulated/0/Music/周杰伦/花海.flac"),observedAt=8000))
            val written=records.records().first {it.title=="花海"}
            records.applyResult(written.id,written.revision,true,"")
            activity=inst.startActivitySync(Intent(context,PreferenceRecordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onView(withContentDescription("筛选待确认")).perform(click())
            onView(withText("起风了")).check(matches(isDisplayed()))
            onView(withText("晴天")).check(doesNotExist())
            onView(withText("全选")).perform(click())
            onView(withContentDescription("调用 Lyrico 写入标签")).check(matches(isNotEnabled()))
            onView(withContentDescription("筛选全部")).perform(click())
            onView(withContentDescription("选择 晴天")).perform(click())
            onView(withContentDescription("调用 Lyrico 写入标签")).check(matches(isEnabled()))
            onView(withText("写入标签 · 1 首")).check(matches(isDisplayed()))
            val folder=File(context.getExternalFilesDir(null),"preference-redesign-qa").apply {mkdirs()}
            inst.uiAutomation.takeScreenshot().let { bitmap ->
                requireNotNull(bitmap)
                File(folder,"records-selected.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            onView(withContentDescription("搜索歌曲、歌手")).perform(replaceText("周杰伦"),closeSoftKeyboard())
            onView(withText("起风了")).check(doesNotExist())
            onView(withText("晴天")).check(matches(isDisplayed()))
            onView(withContentDescription("时间从晚到早")).perform(click())
            onView(withContentDescription("时间从早到晚")).check(matches(isDisplayed()))
            onView(withContentDescription("筛选已写入")).perform(click())
            onView(withText("花海")).check(matches(isDisplayed()))
        }finally {
            activity?.let {inst.runOnMainSync {it.finish()}}
            prefs.edit().putBoolean("enabled",enabled).apply {if(old==null)remove("records")else putString("records",old)}.commit()
        }
    }
}
