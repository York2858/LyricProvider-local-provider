package io.github.proify.lyricon.localprovider

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.data.*
import org.junit.Assert.*
import org.junit.Test

class ImmersiveFavoriteTest {
    @Test fun journalMigratesOldRecordsAndKeepsStateAfterPagedAcknowledgement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("local_provider_preference_outbox", 0)
        val previous = prefs.all.toMap()
        try {
            prefs.edit().clear().commit()
            val value = PreferenceObservation("fixture.player", "paging", "Paging fixture", observedAt = 1000)
            prefs.edit().putString("${value.identity()}:1000", ProviderStore.json.encodeToString(PreferenceObservation.serializer(), value.copy(rating = 60))).commit()
            val journal = io.github.proify.lyricon.localprovider.xposed.PlayerFavoriteJournal(context)
            repeat(52) { journal.record(value.copy(rating = it, favorite = it % 2 == 0)) }
            journal.write(value, true)
            val first = journal.batch()
            @Suppress("DEPRECATION")
            val page = first.getParcelableArrayList<Bundle>("events")!!
            assertEquals(50, page.size)
            assertTrue(first.getBoolean("more"))
            assertEquals(1000, ProviderStore.json.decodeFromString<PreferenceObservation>(page.first().getString("json")!!).observedAt)
            val journalId = first.getString("journal_id")!!
            journal.acknowledge(journalId, page.last().getLong("sequence"))
            @Suppress("DEPRECATION")
            val rest = journal.batch().getParcelableArrayList<Bundle>("events")!!
            assertEquals(4, rest.size)
            try { journal.acknowledge(java.util.UUID.randomUUID().toString(), Long.MAX_VALUE); fail("foreign journal ack") }
            catch (_: IllegalStateException) { }
            journal.acknowledge(journalId, rest.last().getLong("sequence"))
            @Suppress("DEPRECATION")
            val empty = journal.batch().getParcelableArrayList<Bundle>("events")!!
            assertTrue(empty.isEmpty())
            val reopened = io.github.proify.lyricon.localprovider.xposed.PlayerFavoriteJournal(context)
            assertEquals(true, reopened.read(value))
            assertEquals(51, reopened.rating(value))
        } finally {
            prefs.edit().clear().apply { previous.forEach { (key, value) -> when(value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
            } } }.commit()
        }
    }

    @Test fun playerJournalPreservesManualFavoriteUntilNativeStateChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("local_provider_preference_outbox", 0)
        val previous = prefs.all.toMap()
        val native = PreferenceObservation("fixture.player", "journal-fixture", "Journal fixture", favorite = false)
        val journal = io.github.proify.lyricon.localprovider.xposed.PlayerFavoriteJournal(context)
        try {
            prefs.edit().clear().commit()
            journal.observeNative(native)
            journal.write(native, true)
            journal.observeNative(native)
            assertEquals(true, io.github.proify.lyricon.localprovider.xposed.PlayerFavoriteJournal(context).read(native))
            journal.write(native, false, seed = true)
            assertEquals(true, journal.read(native))
            journal.observeNative(native.copy(favorite = true))
            journal.observeNative(native)
            assertEquals(false, journal.read(native))
            journal.write(native, false)
            val queued = prefs.all.values.filterIsInstance<String>().map { ProviderStore.json.decodeFromString<PreferenceObservation>(it) }
            assertEquals(listOf(true, false), queued.sortedBy { it.observedAt }.map { it.favorite })
            assertTrue(queued.all { it.fromLyrics })
        } finally {
            prefs.edit().clear().apply { previous.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
            } } }.commit()
        }
    }

    @Test fun recordsToggleDurablyWithoutChangingPlayerBaselineOrRating() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("playback_preferences", 0)
        val previous = prefs.getString("records", null)
        val enabled = prefs.getBoolean("enabled", true)
        val store = PreferenceRecordStore(context)
        val value = PreferenceObservation("fixture.player", "fixture-id", "Immersive favorite fixture", rating = 60, favorite = false)
        try {
            prefs.edit().remove("records").putBoolean("enabled", true).commit()
            store.observe(value)
            store.setImmersiveFavorite(value.copy(favorite = null, rating = null), true)
            var record = PreferenceRecordStore(context).records().single()
            assertEquals(true, record.favorite)
            assertNotNull(record.favoriteTime)
            assertEquals(60, record.rating)
            assertEquals(false, record.lastFavorite)
            val revision = record.revision
            val favoriteTime = record.favoriteTime
            store.setImmersiveFavorite(value, true)
            assertEquals(revision, store.records().single().revision)
            assertEquals(favoriteTime, store.records().single().favoriteTime)
            store.observe(value)
            assertEquals(true, store.records().single().favorite)
            store.setImmersiveFavorite(value, false)
            record = store.records().single()
            assertEquals(false, record.favorite)
            assertNull(record.favoriteTime)
            assertEquals(60, record.rating)
            assertEquals("pending", record.status)
        } finally {
            prefs.edit().apply { if (previous == null) remove("records") else putString("records", previous) }
                .putBoolean("enabled", enabled).commit()
        }
    }

    @Test fun providerApiRejectsDisabledPlayersAndDoesNotWriteOnQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = ProviderStore(context)
        val old = settings.settings()
        val prefs = context.getSharedPreferences("playback_preferences", 0)
        val previous = prefs.getString("records", null)
        val enabled = prefs.getBoolean("enabled", true)
        val data = Bundle().apply { putString("player", "fixture.player"); putString("title", "Immersive favorite fixture") }
        try {
            prefs.edit().remove("records").putBoolean("enabled", true).commit()
            settings.update { it.copy(players = it.players - "fixture.player") }
            val api = ImmersiveFavoriteStore(context)
            assertFalse(api.call(data).getBoolean("accepted"))
            settings.update { it.copy(players = it.players + "fixture.player") }
            assertTrue(api.call(data).getBoolean("accepted"))
            assertTrue(PreferenceRecordStore(context).records().isEmpty())
            data.putBoolean("favorite", true)
            assertTrue(api.call(data).getBoolean("favorite"))
            prefs.edit().putBoolean("enabled", false).commit()
            data.putBoolean("favorite", false)
            assertFalse(api.call(data).getBoolean("accepted"))
            assertEquals(true, PreferenceRecordStore(context).records().single().favorite)
        } finally {
            settings.update { old }
            prefs.edit().apply { if (previous == null) remove("records") else putString("records", previous) }
                .putBoolean("enabled", enabled).commit()
        }
    }
}
