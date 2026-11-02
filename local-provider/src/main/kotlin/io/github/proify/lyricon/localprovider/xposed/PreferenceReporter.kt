package io.github.proify.lyricon.localprovider.xposed

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import io.github.proify.lyricon.localprovider.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.WeakHashMap

/** Captures player-owned state before the lyric pipeline's metadata deduplication. */
object PreferenceReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val metadata = WeakHashMap<MediaSession, MediaMetadata>()
    private val snapshots = WeakHashMap<MediaSession, String>()
    private var deliveryJob: Job? = null
    private val pathCache = object : LinkedHashMap<String, Pair<Long,List<PreferenceFile>>>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long,List<PreferenceFile>>>?) = size>64
    }
    @Synchronized fun metadata(context: Context, session: MediaSession, value: MediaMetadata) {
        metadata[session] = value
        schedule(context, session, value, session.controller.playbackState)
    }
    @Synchronized fun playback(context: Context, session: MediaSession, value: PlaybackState) {
        val current = metadata[session] ?: return
        schedule(context, session, current, value)
    }
    private fun schedule(context: Context, session: MediaSession, value: MediaMetadata, state: PlaybackState?) {
        if (!RuntimeBridge.settings.recordPreferences || context.packageName !in RuntimeBridge.settings.players) return
        val signature = listOf(value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            value.getString(MediaMetadata.METADATA_KEY_TITLE), value.getString(MediaMetadata.METADATA_KEY_ARTIST),
            value.getString(MediaMetadata.METADATA_KEY_ALBUM), value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            value.getString(MediaMetadata.METADATA_KEY_MEDIA_URI), value.getString("filePath"),
            value.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.toString(), state?.let { favorite(context, it) }).toString()
        if (snapshots[session] == signature) return
        snapshots[session] = signature
        val time = System.currentTimeMillis()
        scope.launch {
            if (!RuntimeBridge.settings.recordPreferences || context.packageName !in RuntimeBridge.settings.players) return@launch
            runCatching {
                val title = value.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
                if (title.isBlank()) return@runCatching
                val artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                val album = value.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
                val duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val rated = value.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
                val favorite = rated?.takeIf { it.isRated }?.let {
                    when(it.ratingStyle) { Rating.RATING_HEART -> it.hasHeart(); Rating.RATING_THUMB_UP_DOWN -> it.isThumbUp; else -> null }
                } ?: state?.let { favorite(context, it) }
                val rating = rated?.let {
                    if(!it.isRated && it.ratingStyle in Rating.RATING_3_STARS..Rating.RATING_PERCENTAGE) return@let 0f
                    when(it.ratingStyle) {
                        Rating.RATING_3_STARS -> it.starRating * 100f / 3
                        Rating.RATING_4_STARS -> it.starRating * 25
                        Rating.RATING_5_STARS -> it.starRating * 20
                        Rating.RATING_PERCENTAGE -> it.percentRating
                        else -> null
                    }
                }?.let { kotlin.math.round(it).toInt().coerceIn(0,100) }
                val files = resolve(context, value, title, artist, album, duration)
                val observation = PreferenceObservation(context.packageName, value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                    title, artist, album, duration, favorite, rating, files.singleOrNull(), if(files.size>1)files else emptyList(), time)
                val outbox = context.getSharedPreferences("local_provider_preference_outbox", Context.MODE_PRIVATE)
                check(outbox.edit().putString("${observation.identity()}:$time", ProviderStore.json.encodeToString(observation)).commit())
                retryPending(context)
            }.onFailure { android.util.Log.w("LocalProvider", "Preference observation unavailable: ${it.javaClass.simpleName}") }
        }
    }
    /** Journal belongs to the player, so killing the module UI cannot lose an observation. */
    @Synchronized fun retryPending(context: Context) {
        if (deliveryJob?.isActive == true) return
        val app = context.applicationContext
        deliveryJob = scope.launch {
            val outbox = app.getSharedPreferences("local_provider_preference_outbox", Context.MODE_PRIVATE)
            deliverPreferenceOutbox(
                pending = {
                    outbox.all.entries.sortedBy { it.key.substringAfterLast(':').toLongOrNull() ?: 0L }
                        .map { it.key to (it.value as String) }
                },
                send = { raw ->
                    val result = app.contentResolver.call(ProviderStore.URI, "preference_observe", null,
                        Bundle().apply { putString("json", raw) })
                    check(result != null)
                },
                acknowledge = { key -> check(outbox.edit().remove(key).commit()) },
                wakeModule = {
                    val token = android.app.PendingIntent.getBroadcast(app, 0,
                        android.content.Intent("io.github.proify.lyricon.localprovider.PREFERENCE_SENDER").setPackage(app.packageName),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    app.sendBroadcast(android.content.Intent().setComponent(android.content.ComponentName(
                        "io.github.proify.lyricon.localprovider", "io.github.proify.lyricon.localprovider.data.PreferenceWakeReceiver"))
                        .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("sender", token))
                },
            )
        }
    }
    private fun favorite(context: Context, state: PlaybackState): Boolean? {
        // Only a verified player/icon convention is interpreted as favorite state.
        if (context.packageName !in setOf("ink.trantor.coneplayer", "ink.trantor.coneplayer.gp")) return null
        val action = state.customActions.firstOrNull { it.name.toString() in setOf("收藏", "取消收藏", "Favorite", "Unfavorite") } ?: return null
        return when(runCatching { context.resources.getResourceEntryName(action.icon) }.getOrNull()) {
            "media3_icon_star_filled" -> true
            "media3_icon_star_unfilled" -> false
            else -> null
        }
    }
    internal fun resolve(context: Context, m: MediaMetadata, title: String, artist: String, album: String, duration: Long): List<PreferenceFile> {
        for (key in listOf("_data", "filePath", "path", MediaMetadata.METADATA_KEY_MEDIA_URI, MediaMetadata.METADATA_KEY_MEDIA_ID)) {
            val raw = m.getString(key)?.trim().orEmpty()
            if (raw.startsWith("content://")) return listOf(PreferenceFile(uri=raw,source="播放器直接提供"))
            val path = if(raw.startsWith("file://")) Uri.parse(raw).path.orEmpty() else raw
            if(path.startsWith("/") && File(path).isFile) return listOf(PreferenceFile(path=path,source="播放器直接提供"))
        }
        val result = mutableListOf<PreferenceFile>()
        val cacheKey=listOf(context.packageName,m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),title,artist,album,duration).joinToString("\u0000")
        val now=android.os.SystemClock.elapsedRealtime()
        synchronized(pathCache){pathCache[cacheKey]?.let { (time,files)->if(now-time<if(files.isEmpty())2000 else 30_000)return files }}
        val projection = arrayOf("_id", "_data", "artist", "album", "duration")
        runCatching { context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "title = ?", arrayOf(title), null)?.use { c ->
            while(c.moveToNext()) {
                if(artist.isNotBlank() && !artist.equals(c.getString(2)?.trim(),true))continue
                if(album.isNotBlank() && !album.equals(c.getString(3)?.trim(),true))continue
                if(duration>0 && kotlin.math.abs(c.getLong(4)-duration)>2000)continue
                result += PreferenceFile(c.getString(1).orEmpty(), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,c.getLong(0)).toString(),"Local Provider 补充")
                if(result.size>=30)break
            }
        } }
        return result.distinctBy { it.path.ifBlank { it.uri } }.sortedBy{it.path.ifBlank{it.uri}}.also { synchronized(pathCache){pathCache[cacheKey]=now to it} }
    }
}
