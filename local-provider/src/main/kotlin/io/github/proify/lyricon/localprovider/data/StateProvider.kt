package io.github.proify.lyricon.localprovider.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import kotlinx.serialization.encodeToString

/** Trusted player packages may read configuration and report their own runtime only. */
class StateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        val store = ProviderStore(app)
        val own = Binder.getCallingUid() == Process.myUid()
        val packages = app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        if (method == "immersive_favorite") {
            if (!own && packages.none { it == "io.github.proify.lyricon" || it == "com.android.systemui" }) {
                throw SecurityException("Only Lyricon may record immersive favorites")
            }
            return ImmersiveFavoriteStore(app).call(requireNotNull(extras))
        }
        val trusted = packages.intersect(if (method == "config") store.settings().knownPlayers else store.settings().players)
        if (!own && trusted.isEmpty()) throw SecurityException("Player has not been enabled")
        return when (method) {
            "preference_observe" -> {
                val raw = extras?.getString("json").orEmpty()
                require(raw.length <= 40_000)
                val observation = ProviderStore.json.decodeFromString<PreferenceObservation>(raw)
                require(own || observation.player in trusted)
                require(observation.title.length <= 2000 && observation.candidates.size <= 30)
                if (observation.fromLyrics) {
                    requireNotNull(observation.favorite)
                    PreferenceRecordStore(app).setImmersiveFavorite(observation, observation.favorite)
                } else PreferenceRecordStore(app).observe(observation)
                Bundle.EMPTY
            }
            "playback" -> {
                val raw = extras?.getString("json").orEmpty()
                require(raw.length <= 4000)
                val playback = ProviderStore.json.decodeFromString<io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot>(raw)
                require(own || playback.player in trusted)
                require(playback.speed.isFinite())
                store.reportPlayback(playback)
                Bundle.EMPTY
            }
            "artwork" -> {
                val player = extras?.getString("player").orEmpty()
                require(own || player in trusted)
                val artwork = extras?.getString("artwork").orEmpty()
                require(artwork.length <= 65000)
                val accepted = store.reportArtwork(player, extras?.getString("title"), extras?.getString("artist"), artwork)
                Bundle().apply { putBoolean("accepted", accepted) }
            }
            "config" -> Bundle().apply {
                putString("json", ProviderStore.json.encodeToString(store.settings()))
                putLong("retry", store.retryVersion())
                putLong("previewUntil", store.previewUntil())
                val seeds = PreferenceRecordStore(app).records().filter { it.player in packages && (it.favorite ?: it.lastFavorite) != null }
                    .take(500).map { PreferenceObservation(it.player, it.mediaId, it.title, it.artist, it.album, it.duration,
                        favorite = it.favorite ?: it.lastFavorite) }
                val raw = ProviderStore.json.encodeToString(seeds)
                if (raw.toByteArray().size <= 180_000) putString("favorite_seeds", raw)
            }
            "report" -> {
                val raw = extras?.getString("json").orEmpty()
                require(raw.length <= 380_000)
                val report = ProviderStore.json.decodeFromString<RuntimeSnapshot>(raw)
                require(own || report.player in trusted)
                store.report(report.copy(updatedAt = System.currentTimeMillis()))
                Bundle.EMPTY
            }
            "find" -> {
                val file = Catalog.find(app, extras?.getString("title"), extras?.getString("artist"), extras?.getString("path"))
                Bundle().apply {
                    file?.let {
                        val song = ProviderStore.json.encodeToString(it.song)
                        if (song.length <= 380_000) {
                            putString("song", song); putString("source", "${if (it.embedded) "音乐内嵌歌词" else "目录歌词"} · ${it.filename}")
                            putBoolean("manual", store.choices()[trackKey(extras?.getString("title"), extras?.getString("artist"))]?.uri?.isNotBlank() == true)
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown operation")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
