package io.github.proify.lyricon.localprovider.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private module storage. Hooked players access only the narrowly scoped provider API. */
class ProviderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_provider_ui", Context.MODE_PRIVATE)
    companion object {
        const val AUTHORITY = "io.github.proify.lyricon.localprovider.state"
        const val ACTION_REFRESH = "io.github.proify.lyricon.localprovider.REFRESH"
        private val previewDemand = io.github.proify.lyricon.localprovider.preview.PreviewDemand()
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        val PLAYBACK_URI: Uri = URI.buildUpon().appendPath("playback").build()
        @Volatile private var currentPlayback = io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
    fun previewUntil(): Long = previewDemand.expiresAt(android.os.SystemClock.elapsedRealtime())
    fun previewDemand(owner: String, active: Boolean) {
        previewDemand.update(owner, active, android.os.SystemClock.elapsedRealtime())
        refreshHosts(settings().players)
    }
    fun playback() = currentPlayback
    fun reportPlayback(value: io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot) {
        // Keep rapidly changing progress in memory; it must never generate disk writes or log entries.
        if (previewUntil() <= android.os.SystemClock.elapsedRealtime() || currentPlayback == value) return
        currentPlayback = value
        context.contentResolver.notifyChange(PLAYBACK_URI, null)
    }
    fun settings(): ProviderSettings = decode(prefs.getString("settings", null), ProviderSettings())
    fun snapshot(): RuntimeSnapshot = decode(prefs.getString("snapshot", null), RuntimeSnapshot())
    fun events(): List<RuntimeEvent> = decode(prefs.getString("events", null), emptyList())
    fun choices(): Map<String, SongChoice> = decode(prefs.getString("choices", null), emptyMap())
    @Synchronized fun update(transform: (ProviderSettings) -> ProviderSettings) {
        val old = settings()
        val transformed = transform(old)
        val value = transformed.copy(revision = maxOf(System.currentTimeMillis(), old.revision + 1),
            knownPlayers = old.knownPlayers + old.players + transformed.players)
        prefs.edit().putString("settings", json.encodeToString(value)).commit()
        Catalog.invalidate()
        refreshHosts(old.players + value.players)
    }
    @Synchronized fun report(value: RuntimeSnapshot) {
        val previous = snapshot()
        val next = if (value.artwork.isBlank() && previous.player == value.player &&
            trackKey(previous.song?.name, previous.song?.artist) == trackKey(value.song?.name, value.song?.artist)) {
            value.copy(artwork = previous.artwork)
        } else value
        val key = trackKey(value.song?.name, value.song?.artist)
        val keptChoices = choices().filter { (songKey, choice) -> !choice.temporary || songKey == key }
        prefs.edit().putString("snapshot", json.encodeToString(next))
            .putString("choices", json.encodeToString(keptChoices))
            .putString("events", json.encodeToString((listOf(RuntimeEvent(
                value.updatedAt, value.player, value.song?.name.orEmpty(), value.message
            )) + events()).take(60))).commit()
        context.contentResolver.notifyChange(URI, null)
    }
    @Synchronized fun reportArtwork(player: String, title: String?, artist: String?, artwork: String): Boolean {
        val current = snapshot()
        if (current.player != player || trackKey(current.song?.name, current.song?.artist) != trackKey(title, artist) ||
            artwork.isBlank()) return false
        if (current.artwork == artwork) return true
        prefs.edit().putString("snapshot", json.encodeToString(current.copy(artwork = artwork))).commit()
        context.contentResolver.notifyChange(URI, null)
        return true
    }
    @Synchronized fun choose(key: String, value: SongChoice) {
        prefs.edit().putString("choices", json.encodeToString(choices() + (key to value))).commit()
        requestRetry()
    }
    @Synchronized fun clearChoice(key: String) {
        prefs.edit().putString("choices", json.encodeToString(choices() - key)).commit()
        requestRetry()
    }
    fun requestRetry() {
        val previous = prefs.getLong("retry", 0)
        prefs.edit().putLong("retry", maxOf(System.currentTimeMillis(), previous + 1)).commit()
        refreshHosts(settings().players)
    }
    fun retryVersion(): Long = prefs.getLong("retry", 0)
    fun clearEvents() { prefs.edit().remove("events").apply() }
    private fun refreshHosts(players: Set<String>) {
        players.forEach {
            runCatching { context.grantUriPermission(it, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(it))
        }
        context.contentResolver.notifyChange(URI, null)
    }
    fun connectPlayers() { refreshHosts(settings().players) }
    private inline fun <reified T> decode(value: String?, fallback: T): T =
        value?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: fallback
}
