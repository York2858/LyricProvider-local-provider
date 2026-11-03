package io.github.proify.lyricon.localprovider.xposed

import android.content.Context
import io.github.proify.lyricon.localprovider.data.PreferenceObservation
import io.github.proify.lyricon.localprovider.data.ProviderStore
import kotlinx.serialization.encodeToString

class PlayerFavoriteJournal(context: Context) {
    private val preferences = context.getSharedPreferences("local_provider_preference_outbox", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    fun read(value: PreferenceObservation): Boolean? {
        val key = "favorite_state:${value.identity()}"
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    fun observeNative(value: PreferenceObservation) = synchronized(lock) {
        val edit = preferences.edit()
        observeNative(edit, value)
        check(edit.commit())
    }

    private fun observeNative(edit: android.content.SharedPreferences.Editor, value: PreferenceObservation) {
        val favorite = value.favorite ?: return
        val id = value.identity()
        val key = "favorite_native:$id"
        val changed = preferences.contains(key) && preferences.getBoolean(key, false) != favorite
        edit.putBoolean(key, favorite)
        if (changed || !preferences.contains("favorite_state:$id")) edit.putBoolean("favorite_state:$id", favorite)
    }

    fun record(value: PreferenceObservation) = synchronized(lock) {
        val edit = preferences.edit()
        observeNative(edit, value)
        value.rating?.let { edit.putInt("rating_state:${value.identity()}", it) }
        enqueue(edit, value)
        check(edit.commit()) { "播放器内的收藏与评分记录保存失败" }
    }

    fun rating(value: PreferenceObservation): Int? = synchronized(lock) {
        val key = "rating_state:${value.identity()}"
        if (preferences.contains(key)) preferences.getInt(key, 0) else null
    }

    fun batch(): android.os.Bundle = synchronized(lock) {
        migrate()
        val pending = preferences.all.entries.filter { it.key.startsWith("event:") && it.value is String }
            .sortedBy { it.key.substringAfter(':').toLong() }
        var bytes = 0
        val entries = arrayListOf<android.os.Bundle>()
        for (entry in pending) {
            val raw = entry.value as String
            val size = raw.toByteArray().size
            if (entries.size >= 50 || (entries.isNotEmpty() && bytes + size > 180_000)) break
            entries += android.os.Bundle().apply { putLong("sequence", entry.key.substringAfter(':').toLong()); putString("json", raw) }
            bytes += size
        }
        android.os.Bundle().apply {
            putString("journal_id", journalId())
            putParcelableArrayList("events", entries)
            putBoolean("more", pending.size > entries.size)
        }
    }

    fun acknowledge(journalId: String, through: Long) = synchronized(lock) {
        check(journalId == journalId()) { "播放器记录库已变化，请重新读取" }
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith("event:") && (it.substringAfter(':').toLongOrNull() ?: Long.MAX_VALUE) <= through }
            .forEach { edit.remove(it) }
        check(edit.commit()) { "播放器记录同步确认失败" }
    }

    private fun journalId(): String = preferences.getString("journal_id", null) ?: java.util.UUID.randomUUID().toString().also {
        check(preferences.edit().putString("journal_id", it).commit())
    }

    private fun migrate() {
        val old = preferences.all.entries.filter { it.value is String && it.key != "journal_id" && !it.key.startsWith("event:") }
            .sortedBy { it.key.substringAfterLast(':').toLongOrNull() ?: 0L }
        if (old.isEmpty()) return
        var clock = preferences.getLong("event_clock", 0)
        val edit = preferences.edit()
        old.forEach {
            clock = maxOf(System.currentTimeMillis(), clock + 1)
            edit.putString("event:$clock", it.value as String).remove(it.key)
        }
        check(edit.putLong("event_clock", clock).commit())
    }

    private fun enqueue(edit: android.content.SharedPreferences.Editor, value: PreferenceObservation) {
        migrate()
        val raw = ProviderStore.json.encodeToString(value)
        require(raw.length <= 40_000) { "收藏与评分记录过大" }
        val sequence = maxOf(System.currentTimeMillis(), preferences.getLong("event_clock", 0) + 1)
        edit.putString("event:$sequence", raw).putLong("event_clock", sequence)
    }

    fun write(value: PreferenceObservation, favorite: Boolean, seed: Boolean = false) = synchronized(lock) {
        val id = value.identity()
        if (seed && preferences.contains("favorite_state:$id")) return@synchronized
        val time = maxOf(System.currentTimeMillis(), preferences.getLong("favorite_clock:$id", 0L) + 1)
        val edit = preferences.edit().putBoolean("favorite_state:$id", favorite)
        if (!seed) {
            val observation = value.copy(favorite = favorite, fromLyrics = true, observedAt = time)
            enqueue(edit, observation)
            edit.putLong("favorite_clock:$id", time)
        }
        check(edit.commit()) { "播放器内的喜爱记录保存失败" }
    }
}
