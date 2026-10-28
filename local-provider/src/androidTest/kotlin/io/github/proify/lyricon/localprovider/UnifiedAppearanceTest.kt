package io.github.proify.lyricon.localprovider

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.search.*
import io.github.proify.lyricon.localprovider.ui.*
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UnifiedAppearanceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun restore(p: SharedPreferences,values: Map<String,*>) {
        p.edit().clear().apply { values.forEach { (key,v) -> when(v) {
            is String -> putString(key,v); is Boolean -> putBoolean(key,v); is Int -> putInt(key,v); is Long -> putLong(key,v); is Float -> putFloat(key,v)
            is Set<*> -> putStringSet(key,v.filterIsInstance<String>().toSet())
        } } }.commit()
    }
    private fun capture(name: String) {
        inst.waitForIdleSync(); Thread.sleep(180)
        val b=inst.uiAutomation.takeScreenshot()
        val root=File(context.getExternalFilesDir(null),"unified-ui-qa").apply { mkdirs() }
        File(root,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    }
    private fun call(a: MainActivity,name: String,vararg values: Any?): Any? {
        val m=MainActivity::class.java.declaredMethods.single { it.name==name }
        m.isAccessible=true; return m.invoke(a,*values)
    }
    private fun field(a: MainActivity,name: String,value: Any?) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value) }
    private val song=Song(name="晚风与海",artist="林间",duration=240000,lyrics=listOf(
        RichLyricLine(begin=0,end=5000,text="风经过窗边",roma="Feng jing guo chuang bian",translation="留下温柔的回声"),
        RichLyricLine(begin=5000,end=10000,text="留下一片晴天",roma="Liu xia yi pian qing tian",translation="把故事写进时间"),
        RichLyricLine(begin=10000,end=15000,text="我们慢慢走远"),RichLyricLine(begin=15000,end=20000,text="把故事交给时间")))
    private var artwork=""
    private fun candidate(source: String="QQMusicProvider")=SearchCandidate(SearchCandidate.digest("ui-layout-$source"),source,song,"蓝色手记",artworkUrl=artwork)
    private fun visible(view: View) { val rect=Rect(); assertTrue("Hidden: ${view.contentDescription}",view.getGlobalVisibleRect(rect)); assertEquals(view.height,rect.height()) }
    private fun geometry(activity: MainActivity,page: String) {
        val views=all(activity.window.decorView)
        fun tagged(tag: String)=views.single { it.tag==tag }
        when(page) {
            "home" -> {
                val hero=tagged("currentSongHero") as ViewGroup
                val cover=hero.findViewWithTag<View>("songCover")
                val title=hero.findViewWithTag<View>("songTitle")
                val c=Rect(); val t=Rect(); cover.getGlobalVisibleRect(c); title.getGlobalVisibleRect(t)
                assertEquals("Home artwork must be square",cover.width,cover.height)
                val image=cover as android.widget.ImageView
                assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,image.scaleType)
                val heroText=all(hero).filterIsInstance<TextView>().map { it.text.toString() }
                assertFalse("Home lyric excerpt must be removed",heroText.contains("风经过窗边"))
                assertFalse(heroText.contains("留下温柔的回声"))
                assertFalse(heroText.contains("Feng jing guo chuang bian"))
                assertTrue("Hero text must sit right of artwork",t.left>=c.right)
            }
            "search" -> {
                val content=MainActivity::class.java.getDeclaredField("content").apply { isAccessible=true }.get(activity) as ViewGroup
                assertEquals("searchField",content.getChildAt(0).tag)
                val rows=all(tagged("searchResults")).filterIsInstance<SongResultCard>()
                assertTrue("Different platforms must remain separate",rows.size>=3)
            }
            "library" -> {
                val tools=MainActivity::class.java.getDeclaredField("footerTools").apply { isAccessible=true }.get(activity) as ViewGroup
                val nav=MainActivity::class.java.getDeclaredField("nav").apply { isAccessible=true }.get(activity) as ViewGroup
                visible(tools); visible(nav); assertTrue(tools.bottom<=nav.top)
            }
            "players" -> assertTrue("Player rows need an app icon",views.any { it.tag=="playerIcon" })
            "settings" -> visible(views.filterIsInstance<TextView>().single { it.text.toString().startsWith("LyricProvider  ") })
            "appearance" -> {
                visible(views.filterIsInstance<TextView>().single { it.text.toString()=="恢复外观默认值" })
                assertEquals(4,views.count { it.tag?.toString()?.startsWith("themeSwatch:")==true })
            }
        }
    }

    @Test fun allPagesAndDialogsUseOneMaterialInFourAppearances() {
        val appearance=context.getSharedPreferences("provider_appearance",0); val oldAppearance=appearance.all.toMap()
        val prefs=context.getSharedPreferences("local_provider_ui",0); val old=prefs.all.toMap()
        val art=File(context.cacheDir,"ui-reference-sea.png")
        inst.context.assets.open("ui-reference-sea.png").use { input -> art.outputStream().use { input.copyTo(it) } }
        artwork="base64:"+android.util.Base64.encodeToString(art.readBytes(),android.util.Base64.NO_WRAP)
        val fixture=candidate()
        val fixtures=listOf(fixture)+listOf("清晨来信","星河漫游","夏日回声","微光").mapIndexed { i,name ->
            fixture.copy(id=SearchCandidate.digest("ui-library-$i"),song=song.copy(name=name,artist=listOf("苏叶","陈与安","鹿野","许眠")[i]))
        }
        val originals=fixtures.associate { f -> File(context.filesDir,"selected-lyrics/${f.id}.json").let { it to it.takeIf { it.exists() }?.readBytes() } }
        try {
            fixtures.forEach { SavedLyrics(context).save(it) }
            ProviderStore(context).update { it.copy(players=it.players+"ui.qa.player") }
            ProviderStore(context).report(RuntimeSnapshot(player="com.neutroncode.mp",updatedAt=System.currentTimeMillis(),song=song,album="蓝色手记",source="QQMusicProvider",message="本地未命中 · 云端已补全",artwork=artwork))
            for(dark in listOf(false,true)) for(glass in listOf(false,true)) {
                appearance.edit().putString("mode",if(dark) "dark" else "light").putBoolean("glass",glass).putBoolean("monet",false).commit()
                val activity=launchTestActivity()
                try {
                    for(page in listOf("home","search","library","settings","appearance","folders","players","cloud","matching","rules","logs","diagnosis","saving")) {
                        inst.runOnMainSync {
                            if(page=="search") {
                                call(activity,"prepareSearch")
                                field(activity,"onlineResults",listOf(candidate(),candidate("NetEase"),candidate("KuGou"),candidate("Soda")))
                            }
                            call(activity,"open",page)
                        }
                        capture("$page-$dark-$glass")
                        if(page=="library") {
                            inst.runOnMainSync {
                                val row=all(activity.window.decorView).filterIsInstance<SongResultCard>().first { it.tag=="songRow:LIBRARY" }
                                row.performClick()
                                assertTrue(all(activity.window.decorView).any { it.tag=="libraryVersions" && it.visibility==View.VISIBLE })
                            }
                            capture("library-expanded-$dark-$glass")
                            inst.runOnMainSync { all(activity.window.decorView).filterIsInstance<SongResultCard>().first { it.tag=="songRow:LIBRARY" }.performClick() }
                        }
                        inst.runOnMainSync {
                            geometry(activity,page)
                            val palette=ProviderTheme.colors(activity)
                            assertEquals("Theme mode changed while rendering $page",dark,androidx.core.graphics.ColorUtils.calculateLuminance(palette.background)<.3)
                            assertEquals("Status icons must contrast with the page",!dark,androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).isAppearanceLightStatusBars)
                            val views=all(activity.window.decorView)
                            val title=views.filterIsInstance<TextView>().firstOrNull { it.text.toString()==when(page) {
                                "home" -> "当前歌词"; "search" -> "搜索歌词"; "library" -> "歌词库"; "settings" -> "设置"; "appearance" -> "外观与布局"; "folders" -> "歌词目录"
                                "players" -> "播放器管理"; "cloud" -> "云端歌词源"; "matching" -> "匹配评分规则"; "rules" -> "歌词过滤"; "logs" -> "运行记录"; "saving" -> "保存规则"; else -> "匹配详情"
                            } }
                            assertNotNull("Missing page heading $page",title)
                            assertTrue(title!!.getGlobalVisibleRect(Rect()))
                            if(page in listOf("settings","appearance","cloud","players","saving")) {
                                assertFalse("Old switches leaked into $page",views.any { it is com.google.android.material.switchmaterial.SwitchMaterial })
                            }
                        }
                    }
                    for((name,method,args) in listOf(
                        Triple("dialog-material","materialControls",emptyArray<Any?>()),
                        Triple("dialog-corner","cornerControls",emptyArray<Any?>()),
                        Triple("dialog-player","addPlayer",emptyArray<Any?>()),
                        Triple("dialog-filter","addRule",arrayOf<Any?>(false)),
                        Triple("dialog-color","customColor",arrayOf<Any?>("background","全局背景色")))) {
                        inst.runOnMainSync { call(activity,method,*args) }
                        capture("$name-$dark-$glass")
                        if(name in listOf("dialog-player","dialog-filter","dialog-color")) {
                            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java))
                                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                                .check { view,error ->
                                    if(error!=null) throw error
                                    val box=Rect(); val outer=Rect()
                                    view.getGlobalVisibleRect(box); view.rootView.getGlobalVisibleRect(outer)
                                    val inset=(16*view.resources.displayMetrics.density).toInt()
                                    assertTrue("Dialog input touches left edge",box.left-outer.left>=inset)
                                    assertTrue("Dialog input touches right edge",outer.right-box.right>=inset)
                                }
                        }
                        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("取消"))
                            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()))
                            .perform(object: androidx.test.espresso.ViewAction {
                                override fun getDescription()="Dismiss audited dialog"
                                override fun getConstraints(): org.hamcrest.Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
                                override fun perform(controller: androidx.test.espresso.UiController,view: View) { assertTrue(view.performClick()); controller.loopMainThreadUntilIdle() }
                            })
                    }
                    inst.runOnMainSync { call(activity,"openCandidate",fixture) }
                    capture("preview-$dark-$glass")
                    inst.runOnMainSync {
                        val modes=all(activity.window.decorView).single { it.tag=="previewModes" } as ViewGroup
                        assertEquals(1,(0 until modes.childCount).count { modes.getChildAt(it).isSelected })
                        val button=all(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString()=="使用这份歌词" }
                        val rect=Rect(); assertTrue(button.getGlobalVisibleRect(rect)); assertEquals(button.height,rect.height())
                    }
                    var dialog: AlertDialog?=null
                    inst.runOnMainSync { dialog=MatchScoreDialog.show(activity,MatchTarget("晚风与海","林间","蓝色手记",240000),fixture,"QQ 音乐",MatchRules()) }
                    capture("score-$dark-$glass")
                    inst.runOnMainSync { assertNotNull(dialog!!.window!!.decorView.background); dialog!!.dismiss(); call(activity,"open","search") }
                    var editing: AlertDialog?=null
                    inst.runOnMainSync { editing=call(activity,"editSearchReference") as AlertDialog }
                    capture("edit-reference-$dark-$glass")
                    inst.runOnMainSync { editing?.dismiss() }
                } finally { inst.runOnMainSync { activity.finish() }; inst.waitForIdleSync() }
            }
        } finally {
            restore(appearance,oldAppearance); restore(prefs,old)
            originals.forEach { (file,bytes) -> if(bytes==null) file.delete() else file.writeBytes(bytes) }
            art.delete()
        }
    }

    @Test fun newAppearancePersistsAndResetKeepsProviderData() {
        val prefs=context.getSharedPreferences("provider_appearance",0); val old=prefs.all.toMap()
        val business=context.getSharedPreferences("local_provider_ui",0).all.toMap()
        try {
            prefs.edit().clear().commit(); assertFalse(ProviderAppearance.glass(context))
            ProviderAppearance.setGlass(context,true); ProviderAppearance.setColor(context,"background",0xFF93CFF5.toInt())
            ProviderAppearance.setColor(context,"border",0xFF4D86C4.toInt()); ProviderAppearance.setBlur(context,21); ProviderAppearance.setOpacity(context,61)
            val other=context.createConfigurationContext(context.resources.configuration)
            assertTrue(ProviderAppearance.glass(other)); assertEquals(21,ProviderAppearance.blur(other)); assertEquals(61,ProviderAppearance.opacity(other))
            assertEquals(0xFF93CFF5.toInt(),ProviderAppearance.background(other)); assertEquals(0xFF4D86C4.toInt(),ProviderAppearance.border(other))
            ProviderAppearance.reset(context); assertFalse(ProviderAppearance.glass(context)); assertNull(ProviderAppearance.background(context)); assertNull(ProviderAppearance.border(context))
            assertTrue("Appearance reset must keep provider data",business==context.getSharedPreferences("local_provider_ui",0).all.toMap())
        } finally { restore(prefs,old) }
    }
}
