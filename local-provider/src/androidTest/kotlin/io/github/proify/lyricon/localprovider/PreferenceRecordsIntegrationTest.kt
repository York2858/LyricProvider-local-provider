package io.github.proify.lyricon.localprovider

import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.ui.PreferenceRecordsActivity
import io.github.proify.lyricon.localprovider.xposed.PreferenceReporter
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreferenceRecordsIntegrationTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun await(check:()->Boolean){val deadline=System.currentTimeMillis()+10000;while(!check()){runBlocking { PlayerPreferenceClient.collectPlayer(context, context.packageName) };assertTrue("Timed out",System.currentTimeMillis()<deadline);Thread.sleep(100)}}
    @Test fun capturesRatingClearsAndPreservesPendingWorkAcrossLateResults() {
        val settings=ProviderStore(context)
        val old=settings.settings()
        val prefs=context.getSharedPreferences("playback_preferences",0)
        val oldRecords=prefs.getString("records",null)
        val records=PreferenceRecordStore(context)
        var session:MediaSession?=null
        try {
            prefs.edit().remove("records").putBoolean("enabled",true).commit()
            settings.update{it.copy(players=it.players+context.packageName)}
            RuntimeBridge.initialize(context){}
            await { context.packageName in RuntimeBridge.settings.players }
            session=MediaSession(context,"Preference fixture")
            fun send(value:Rating){PreferenceReporter.metadata(context,session!!,MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,"fixture")
                .putString(MediaMetadata.METADATA_KEY_TITLE,"Preference fixture").putRating(MediaMetadata.METADATA_KEY_USER_RATING,value).build())}
            send(Rating.newStarRating(Rating.RATING_5_STARS,3f))
            await{records.records().any{it.rating==60}}
            val first=records.records().single()
            assertFalse(first.ready)
            send(Rating.newStarRating(Rating.RATING_5_STARS,4f))
            await{records.records().single().rating==80}
            records.applyResult(first.id,first.revision,true,"old success")
            assertEquals("pending",records.records().single().status)
            send(Rating.newUnratedRating(Rating.RATING_5_STARS))
            await{records.records().single().rating==0}
            val current=records.records().single()
            assertNull(current.favorite)
            records.applyResult(current.id,current.revision,true,"ok")
            assertEquals("written",records.records().single().status)
        }finally{
            session?.release();settings.update{old}
            if(oldRecords==null)prefs.edit().remove("records").commit()else prefs.edit().putString("records",oldRecords).commit()
        }
    }
    @Test fun rapidProgressDoesNotCancelRatingAndDeletedWorkStaysDeleted() {
        val settings = ProviderStore(context)
        val old = settings.settings()
        val prefs = context.getSharedPreferences("playback_preferences",0)
        val oldRecords = prefs.getString("records",null)
        val oldEnabled = prefs.getBoolean("enabled",true)
        val records = PreferenceRecordStore(context)
        val session = MediaSession(context,"Preference progress fixture")
        try {
            prefs.edit().remove("records").putBoolean("enabled",true).commit()
            settings.update { it.copy(players=it.players+context.packageName) }
            RuntimeBridge.initialize(context) {}
            await { context.packageName in RuntimeBridge.settings.players }
            val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,"progress-fixture")
                .putString(MediaMetadata.METADATA_KEY_TITLE,"Progress fixture")
                .putRating(MediaMetadata.METADATA_KEY_USER_RATING,Rating.newStarRating(Rating.RATING_5_STARS,5f)).build()
            PreferenceReporter.metadata(context,session,metadata)
            repeat(100) { position ->
                PreferenceReporter.playback(context,session,android.media.session.PlaybackState.Builder()
                    .setState(android.media.session.PlaybackState.STATE_PLAYING,position.toLong(),1f).build())
            }
            await { records.records().any { it.title=="Progress fixture" && it.rating==100 } }
            val current = records.records().single { it.title=="Progress fixture" }
            records.delete(setOf(current.id))
            records.observe(PreferenceObservation(context.packageName,"progress-fixture","Progress fixture",rating=100))
            assertFalse(records.records().single { it.id==current.id }.hasChanges)
            records.observe(PreferenceObservation(context.packageName,"progress-fixture","Progress fixture",rating=80))
            assertEquals(80,records.records().single { it.id==current.id }.rating)
        } finally {
            session.release(); settings.update { old }
            prefs.edit().putBoolean("enabled",oldEnabled).apply {
                if(oldRecords==null)remove("records") else putString("records",oldRecords)
            }.commit()
        }
    }

    @Test fun managementPageCollectsRetainedOutboxWithoutANewPlaybackEvent() {
        val settings = ProviderStore(context)
        val oldSettings = settings.settings()
        val prefs = context.getSharedPreferences("playback_preferences",0)
        val oldRecords = prefs.getString("records",null)
        val oldEnabled = prefs.getBoolean("enabled",true)
        val outbox = context.getSharedPreferences("local_provider_preference_outbox",0)
        val oldOutbox = outbox.all.toMap()
        try {
            prefs.edit().remove("records").putBoolean("enabled",true).commit()
            settings.update { it.copy(players=it.players+context.packageName) }
            RuntimeBridge.initialize(context) {}
            await { context.packageName in RuntimeBridge.settings.players }
            val observation = PreferenceObservation(context.packageName,"retained-fixture","Retained delivery fixture",rating=100)
            val key = "${observation.identity()}:${observation.observedAt}"
            outbox.edit().clear().putString(key,ProviderStore.json.encodeToString(observation)).commit()
            settings.connectPlayers()
            Thread.sleep(300)
            assertFalse(PreferenceRecordStore(context).records().any { it.id==observation.identity() })
            runBlocking { PlayerPreferenceClient.collectPlayer(context, context.packageName) }
            await { PreferenceRecordStore(context).records().any { it.id==observation.identity() && it.rating==100 } }
            await { !outbox.contains(key) }
        } finally {
            settings.update { oldSettings }
            prefs.edit().putBoolean("enabled",oldEnabled).apply {
                if(oldRecords==null)remove("records") else putString("records",oldRecords)
            }.commit()
            outbox.edit().clear().apply { oldOutbox.forEach { (key,value) -> when(value) {
                is String -> putString(key,value)
                is Long -> putLong(key,value)
                is Int -> putInt(key,value)
                is Boolean -> putBoolean(key,value)
            } } }.commit()
        }
    }

    @Test fun recordPageSelectsPreviewsCallsLyricoAndReceivesFailureForMissingFile() {
        val prefs=context.getSharedPreferences("playback_preferences",0)
        val old=prefs.getString("records",null)
        var activity:android.app.Activity?=null
        try {
            prefs.edit().remove("records").commit()
            val store=PreferenceRecordStore(context)
            store.observe(PreferenceObservation("com.neutroncode.mp","handoff","Hand-off fixture",rating=60,
                file=PreferenceFile("/storage/emulated/0/Music/not-found-fixture.flac",source="播放器直接提供")))
            activity=inst.startActivitySync(Intent(context,PreferenceRecordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onView(withText("全选可写入")).perform(click())
            onView(withText("调用 Lyrico 写入标签")).perform(click())
            onView(withText("写入预览 · 1 首")).check(matches(isDisplayed()))
            onView(withText("交给 Lyrico 写入")).perform(click())
            // Lyrico may first request legacy storage permissions on a fresh install.
            val device=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            device.executeShellCommand("pm grant com.lonx.lyrico.debug android.permission.READ_EXTERNAL_STORAGE").close()
            device.executeShellCommand("pm grant com.lonx.lyrico.debug android.permission.WRITE_EXTERNAL_STORAGE").close()
            await{store.records().single().status=="failed"}
            assertTrue(store.records().single().message.contains("不存在"))
            onView(withText("未完成")).check(matches(isDisplayed()))
        }finally{activity?.let{inst.runOnMainSync{it.finish()}};if(old==null)prefs.edit().remove("records").commit()else prefs.edit().putString("records",old).commit()}
    }
    @Test fun deletingRecordFromPageKeepsTheAudioFile() {
        val prefs=context.getSharedPreferences("playback_preferences",0)
        val old=prefs.getString("records",null)
        val file=java.io.File(context.cacheDir,"delete-record-fixture.wav").apply { writeText("untouched fixture") }
        var activity:android.app.Activity?=null
        try {
            prefs.edit().remove("records").commit()
            val store=PreferenceRecordStore(context)
            store.observe(PreferenceObservation("com.neutroncode.mp","delete-fixture","Delete fixture",rating=100,
                file=PreferenceFile(path=file.path,source="手动确认")))
            activity=inst.startActivitySync(Intent(context,PreferenceRecordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onView(withText("删除此记录")).perform(click())
            onView(withText("删除")).perform(click())
            await { store.records().none { it.hasChanges } }
            assertEquals("untouched fixture",file.readText())
        } finally {
            activity?.let { inst.runOnMainSync { it.finish() } };file.delete()
            if(old==null)prefs.edit().remove("records").commit() else prefs.edit().putString("records",old).commit()
        }
    }

    @Test fun manualUriHandoffWritesFileAndReturnsToRecordPage() {
        val prefs=context.getSharedPreferences("playback_preferences",0)
        val old=prefs.getString("records",null)
        val folder=java.io.File(context.cacheDir,"preference-test").apply{mkdirs()}
        val file=java.io.File(folder,"fixture.wav")
        var activity:android.app.Activity?=null
        try {
            // A valid one-second PCM WAV, owned by the provider and granted to Lyrico.
            val pcm=ByteArray(16000*2)
            val bytes=java.nio.ByteBuffer.allocate(44+pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size).put(pcm)
            file.writeBytes(bytes.array())
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.preference.testfiles",file)
            prefs.edit().remove("records").commit()
            val store=PreferenceRecordStore(context)
            store.observe(PreferenceObservation("com.neutroncode.mp","manual-fixture","Manual URI fixture",rating=60,favorite=true,
                file=PreferenceFile(uri=uri.toString(),source="手动确认")))
            val original=store.records().single()
            activity=inst.startActivitySync(Intent(context,PreferenceRecordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onView(withText("全选可写入")).perform(click())
            onView(withText("调用 Lyrico 写入标签")).perform(click())
            onView(withText("交给 Lyrico 写入")).perform(click())
            await{store.records().single().status!="pending"}
            assertEquals(store.records().single().message,"written",store.records().single().status)
            val tags=context.contentResolver.openFileDescriptor(uri,"r")!!.use { com.kyant.taglib.TagLib.getMetadata(it.dup().detachFd(),false)!!.propertyMap }
            assertEquals(original.favoriteTime,tags["MOOD"]?.firstOrNull())
            assertEquals("60",tags["RATING"]?.firstOrNull())
            onView(withText("已写入")).check(matches(isDisplayed()))
        }finally{
            activity?.let{inst.runOnMainSync{it.finish()}}
            file.delete();folder.delete()
            if(old==null)prefs.edit().remove("records").commit()else prefs.edit().putString("records",old).commit()
        }
    }
}
