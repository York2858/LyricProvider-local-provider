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
    fun applyResult(id: String, revision: Long, success: Boolean, message: String) = synchronized(lock) {
        save(records().map { if (it.id == id && it.revision == revision) it.copy(
            status = if (success) "written" else "failed", message = message.take(400)) else it })
    }
    private fun save(records: List<PreferenceRecord>) {
        prefs.edit().putString("records", ProviderStore.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PreferenceRecord.serializer()), records)).commit()
        context.contentResolver.notifyChange(ProviderStore.URI.buildUpon().appendPath("preferences").build(), null)
    }
}
