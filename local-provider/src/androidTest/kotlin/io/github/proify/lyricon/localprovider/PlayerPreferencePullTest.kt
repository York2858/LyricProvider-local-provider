package io.github.proify.lyricon.localprovider

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerPreferencePullTest {
    @Test fun managementReadsPlayerRecordsAndAcknowledgesOnlyAfterSaving() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("playback_preferences", 0)
        val previous = prefs.all.toMap()
        try {
            prefs.edit().remove("records").putBoolean("enabled", true).commit()
            assertTrue(PlayerPreferenceClient.collectPlayer(context, "io.github.proify.lyricon") >= 7)
            val store = PreferenceRecordStore(context)
            val record = store.records().single { it.title == "Player storage fixture" }
            assertEquals(true, record.favorite)
            assertEquals(false, record.lastFavorite)
            assertEquals(0, record.rating)
            assertNotNull(record.favoriteTime)
            val revision = record.revision
            assertEquals(0, PlayerPreferenceClient.collectPlayer(context, "io.github.proify.lyricon"))
            assertEquals(revision, store.records().single().revision)
            store.delete(setOf(record.id))
            PlayerPreferenceClient.collectPlayer(context, "io.github.proify.lyricon")
            assertFalse(store.records().single().hasChanges)
        } finally {
            prefs.edit().clear().apply { previous.forEach { (key, value) -> when(value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
            } } }.commit()
            context.sendBroadcast(Intent("lyricon.test.FINISH_PLAYER_TEST").setPackage("io.github.proify.lyricon"))
        }
    }

    @Test fun repeatedBatchDoesNotRecreateDeletedOrWrittenWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("playback_preferences", 0)
        val previous = prefs.all.toMap()
        try {
            prefs.edit().clear().putBoolean("enabled", true).commit()
            val store = PreferenceRecordStore(context)
            val journal = java.util.UUID.randomUUID().toString()
            val observation = PreferenceObservation("fixture.player", "id", "Rating fixture", rating = 60)
            store.importPlayerBatch("fixture.player", journal, listOf(1L to observation))
            val record = store.records().single()
            store.applyResult(record.id, record.revision, true, "written")
            store.importPlayerBatch("fixture.player", journal, listOf(1L to observation))
            assertEquals("written", store.records().single().status)
            store.delete(setOf(record.id))
            store.importPlayerBatch("fixture.player", journal, listOf(1L to observation))
            assertFalse(store.records().single().hasChanges)
            store.importPlayerBatch("fixture.player", journal, listOf(2L to observation.copy(rating = 80)))
            assertEquals(80, store.records().single().rating)
        } finally {
            prefs.edit().clear().apply { previous.forEach { (key, value) -> when(value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
            } } }.commit()
        }
    }
}
