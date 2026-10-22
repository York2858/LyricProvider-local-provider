package io.github.proify.lyricon.localprovider.data

import android.content.Context
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class SavedLyric(val id: String, val source: String, val album: String, val song: Song, val savedAt: Long,
                      val artworkUrl: String = "")

/** Saved selections live in the module's private storage, never in a player's sandbox. */
class SavedLyrics(context: Context) {
    companion object { private val parsed = FileIndex<SavedLyric>() }
    private val folder = File(context.filesDir, "selected-lyrics")
    fun save(candidate: SearchCandidate): String {
        require(candidate.song.lyrics.orEmpty().isNotEmpty())
        require(candidate.id.matches(Regex("[a-f0-9]{64}")))
        folder.mkdirs()
        val value = SavedLyric(candidate.id, candidate.source, candidate.album, candidate.song, System.currentTimeMillis(), candidate.artworkUrl)
        val file = File(folder, "${candidate.id}.json")
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(ProviderStore.json.encodeToString(value).toByteArray()); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        parsed.forget(file.absolutePath)
        Catalog.invalidate()
        return "saved:${candidate.id}"
    }
    fun get(uri: String): SavedLyric? {
        val id = uri.removePrefix("saved:")
        if (!uri.startsWith("saved:") || !id.matches(Regex("[a-f0-9]{64}"))) return null
        val file = File(folder, "$id.json")
        if (!file.isFile) { parsed.forget(file.absolutePath); return null }
        return parsed.read(file.absolutePath, file.lastModified(), file.length()) {
            runCatching { ProviderStore.json.decodeFromString<SavedLyric>(file.readText()) }.getOrNull()
        }
    }
    fun list(): List<SavedLyric> = folder.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { get("saved:${it.nameWithoutExtension}") }.sortedByDescending { it.savedAt }
    fun files(): List<LyricFile> = list().map { LyricFile("saved:${it.id}", it.song.name.orEmpty(),
        "已保存 · ${it.source}", it.savedAt, it.song) }
}
