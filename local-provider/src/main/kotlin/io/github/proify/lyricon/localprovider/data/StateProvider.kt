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
        val trusted = packages.intersect(if (method == "config") store.settings().knownPlayers else store.settings().players)
        if (!own && trusted.isEmpty()) throw SecurityException("Player has not been enabled")
        return when (method) {
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
                            putString("song", song); putString("source", "目录歌词 · ${it.filename}")
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
