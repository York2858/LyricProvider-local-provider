package io.github.proify.lyricon.localprovider.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class PreferenceFile(val path: String = "", val uri: String = "", val source: String = "")

@Serializable
data class PreferenceObservation(
    val player: String, val mediaId: String = "", val title: String, val artist: String = "",
    val album: String = "", val duration: Long = 0, val favorite: Boolean? = null,
    val rating: Int? = null, val file: PreferenceFile? = null,
    val candidates: List<PreferenceFile> = emptyList(), val observedAt: Long = System.currentTimeMillis(),
    val fromLyrics: Boolean = false,
) {
    fun identity(): String {
        val key = listOf(player, mediaId, title.trim(), artist.trim(), album.trim(), if(mediaId.isBlank())duration.toString()else "")
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class PreferenceRecord(
    val id: String, val revision: Long, val player: String, val mediaId: String,
    val title: String, val artist: String, val album: String, val duration: Long,
    val file: PreferenceFile? = null, val candidates: List<PreferenceFile> = emptyList(),
    val favorite: Boolean? = null, val favoriteTime: String? = null, val rating: Int? = null,
    val updatedAt: Long, val status: String = "pending", val message: String = "",
    val lastFavorite: Boolean? = null, val lastRating: Int? = null,
) {
    val ready: Boolean get() = file?.let { it.path.isNotBlank() || it.uri.isNotBlank() } == true
    val hasChanges: Boolean get() = favorite != null || rating != null
}

/** Unknown values never clear known values. Repeated snapshots do not create new work. */
object PreferenceRecordReducer {
    fun merge(old: PreferenceRecord?, value: PreferenceObservation): PreferenceRecord {
        require(value.player.isNotBlank() && value.title.isNotBlank())
        require(value.rating == null || value.rating in 0..100)
        val favoriteChanged = value.favorite != null && value.favorite != old?.lastFavorite &&
            (old?.lastFavorite != null || value.favorite)
        val ratingChanged = value.rating != null && value.rating != old?.lastRating &&
            (old?.lastRating != null || value.rating > 0)
        val file = old?.file?.takeIf { it.source == "手动确认" } ?: value.file ?: old?.file
        val candidates = value.candidates.ifEmpty { old?.candidates.orEmpty() }
        val pathChanged = old != null && (old.file != file || old.candidates != candidates)
        val changed = favoriteChanged || ratingChanged || pathChanged
        val favorite = if (favoriteChanged) value.favorite else old?.favorite
        val time = if (favoriteChanged) {
            if (value.favorite == true) SimpleDateFormat("yyyy-MM-ddHH:mm:ss", Locale.ROOT).format(Date(value.observedAt)) else null
        } else old?.favoriteTime
        return PreferenceRecord(
            id = value.identity(), revision = if (changed || old == null) maxOf(value.observedAt, (old?.revision ?: 0) + 1) else old.revision,
            player = value.player, mediaId = value.mediaId, title = value.title, artist = value.artist,
            album = value.album, duration = value.duration, file = file, candidates = candidates,
            favorite = favorite, favoriteTime = time, rating = if (ratingChanged) value.rating else old?.rating,
            updatedAt = if (changed || old == null) value.observedAt else old.updatedAt,
            status = if (changed) "pending" else old?.status ?: "pending",
            message = if (changed) "" else old?.message.orEmpty(),
            lastFavorite = value.favorite ?: old?.lastFavorite, lastRating = value.rating ?: old?.lastRating,
        )
    }
}

class PreferenceRecordStore(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("playback_preferences", android.content.Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    fun records(): List<PreferenceRecord> = runCatching {
        ProviderStore.json.decodeFromString<List<PreferenceRecord>>(prefs.getString("records", "[]")!!)
    }.getOrDefault(emptyList())
    fun enabled() = prefs.getBoolean("enabled", true)
    fun immersiveRecord(value: PreferenceObservation): PreferenceRecord? {
        val all = records()
        return all.firstOrNull { it.id == value.identity() } ?: all.filter {
            it.player == value.player && it.title.trim() == value.title.trim() && it.artist.trim() == value.artist.trim() &&
                it.album.trim() == value.album.trim() && (value.mediaId.isBlank() || it.mediaId.isBlank())
        }.singleOrNull()
    }
    fun setImmersiveFavorite(value: PreferenceObservation, favorite: Boolean) = synchronized(lock) {
        check(enabled()) { "收藏与评分记录已关闭" }
        val old = immersiveRecord(value)
        if (old?.favorite == favorite) return@synchronized
        val next = manualFavorite(old, value, favorite)
        save((listOf(next) + records().filterNot { it.id == next.id }).sortedByDescending { it.updatedAt })
    }

    fun importPlayerBatch(player: String, journalId: String, events: List<Pair<Long, PreferenceObservation>>) = synchronized(lock) {
        check(enabled()) { "收藏与评分记录已关闭" }
        require(player.isNotBlank() && player.length <= 300 && journalId.matches(Regex("[a-fA-F0-9-]{36}")))
        require(events.size <= 50 && events.zipWithNext().all { it.first.first < it.second.first })
        val cursorKey = "player_cursor:$player:$journalId"
        val cursor = prefs.getLong(cursorKey, 0)
        val all = records().associateBy { it.id }.toMutableMap()
        events.filter { it.first > cursor }.forEach { (sequence, value) ->
            require(sequence > 0 && value.player == player && value.title.isNotBlank() && value.title.length <= 2000 &&
                value.candidates.size <= 30 && (value.rating == null || value.rating in 0..100))
            val old = all[value.identity()] ?: if (value.fromLyrics) all.values.filter {
                it.player == player && it.title.trim() == value.title.trim() && it.artist.trim() == value.artist.trim() &&
                    it.album.trim() == value.album.trim() && (value.mediaId.isBlank() || it.mediaId.isBlank())
            }.singleOrNull() else null
            val next = if (value.fromLyrics) manualFavorite(old, value, requireNotNull(value.favorite))
                else PreferenceRecordReducer.merge(old, value)
            all[next.id] = next
        }
        val updated = all.values.sortedByDescending { it.updatedAt }.let { list ->
            list.filter { it.hasChanges && it.status != "written" } + list.filter { !it.hasChanges || it.status == "written" }.take(1000)
        }
        val through = events.lastOrNull()?.first ?: cursor
        check(prefs.edit().putString("records", ProviderStore.json.encodeToString(updated))
            .putLong(cursorKey, maxOf(cursor, through)).commit()) { "收藏与评分汇总保存失败" }
        context.contentResolver.notifyChange(ProviderStore.URI.buildUpon().appendPath("preferences").build(), null)
    }

    private fun manualFavorite(old: PreferenceRecord?, value: PreferenceObservation, favorite: Boolean): PreferenceRecord {
        if (old?.favorite == favorite) return old
        val baseline = old ?: PreferenceRecordReducer.merge(null, value.copy(favorite = null, rating = null))
        val now = value.observedAt.coerceAtMost(System.currentTimeMillis())
        return baseline.copy(favorite = favorite,
            favoriteTime = if (favorite) SimpleDateFormat("yyyy-MM-ddHH:mm:ss", Locale.ROOT).format(Date(now)) else null,
            revision = maxOf(now, baseline.revision + 1), updatedAt = now, status = "pending", message = "")
    }
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean("enabled", enabled).commit(); ProviderStore(context).requestRetry() }
    fun observe(value: PreferenceObservation) = synchronized(lock) {
        if (!enabled()) return@synchronized
        val all = records()
        val old = all.find { it.id == value.identity() }
        val next = PreferenceRecordReducer.merge(old, value)
        if (next != old) {
            val updated=(listOf(next) + all.filterNot { it.id == next.id }).sortedByDescending { it.updatedAt }
            // Never evict unsubmitted operations to make room for written history or baselines.
            save(updated.filter{it.hasChanges&&it.status!="written"} + updated.filter{!it.hasChanges||it.status=="written"}.take(1000))
        }
    }
    fun choose(id: String, file: PreferenceFile) = synchronized(lock) {
        save(records().map { if (it.id == id) it.copy(file = file.copy(source = "手动确认"),
            revision = maxOf(System.currentTimeMillis(), it.revision + 1), status = "pending", message = "") else it })
    }
    fun delete(ids: Set<String>) = synchronized(lock) {
        // Keep the observed baseline: a repeated player snapshot must not recreate deleted work.
        save(records().map { if (it.id in ids) it.copy(favorite = null, favoriteTime = null,
            rating = null, status = "pending", message = "") else it })
    }
    fun applyResult(id: String, revision: Long, success: Boolean, message: String) = synchronized(lock) {
        save(records().map { if (it.id == id && it.revision == revision) it.copy(
            status = if (success) "written" else "failed", message = message.take(400)) else it })
    }
    private fun save(records: List<PreferenceRecord>) {
        prefs.edit().putString("records", ProviderStore.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PreferenceRecord.serializer()), records)).commit()
        context.contentResolver.notifyChange(ProviderStore.URI.buildUpon().appendPath("preferences").build(), null)
    }
}
