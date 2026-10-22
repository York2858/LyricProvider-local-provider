package io.github.proify.lyricon.localprovider.local

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.data.FileIndex
import io.github.proify.lyricon.localprovider.data.RuntimeBridge
import io.github.proify.lyricon.localprovider.model.LyricsProvider
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.xposed.LocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Player-side fallback for plain paths unavailable to the module's SAF catalog. */
class ExternalLrcProvider : LyricsProvider {
    override val id = "ExternalLrc"
    private val parsed = FileIndex<LyricsResult>()
    private var index = emptyMap<String, List<File>>()
    private var indexedAt = 0L
    private var config = ""
    override suspend fun search(query: String?, trackName: String?, artistName: String?, albumName: String?, limit: Int): List<LyricsResult> = emptyList()
    private fun normalize(value: String?) = value.orEmpty().filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    private fun configure() {
        val settings = RuntimeBridge.settings
        val key = settings.revision.toString()
        if (key != config) {
            config = key; parsed.clear(); index = emptyMap(); indexedAt = 0
        }
    }
    private fun read(file: File): LyricsResult? = parsed.read(file.absolutePath, file.lastModified(), file.length()) {
        if (file.length() > 2_000_000) return@read null
        val raw = runCatching { file.readText() }.getOrNull() ?: return@read null
        if (io.github.proify.lyricon.localprovider.lyrics.AtomicLyricFile.clearlyInvalid(raw)) return@read null
        val doc = EnhanceLrcParser.parse(raw)
        val lines = LocalProvider.normalizeForTripleLine(doc.lines).filter { line ->
            listOf("浣滄洸", "浣滆瘝", "缂栨洸").none { line.text.orEmpty().contains(it, true) }
        }
        if (lines.isEmpty()) null else LyricsResult(trackName = doc.metadata["ti"], artistName = doc.metadata["ar"],
            albumName = doc.metadata["al"], rich = lines)
    }
    suspend fun searchByAudioFile(audioFilePath: String, trackName: String?, artistName: String?, albumName: String?): LyricsResult? = withContext(Dispatchers.IO) {
        synchronized(this@ExternalLrcProvider) {
            configure()
            val audio = File(audioFilePath)
            if (!audio.isFile) return@synchronized null
            val file = File(audio.parentFile ?: return@synchronized null, "${audio.nameWithoutExtension}.lrc")
            if (!file.isFile) return@synchronized null
            read(file)?.let { LyricsResult(trackName = trackName ?: it.trackName, artistName = artistName ?: it.artistName,
                albumName = albumName ?: it.albumName, rich = it.rich) }
        }
    }
    suspend fun searchByMetadata(trackName: String?, artistName: String?, albumName: String?): LyricsResult? = withContext(Dispatchers.IO) {
        synchronized(this@ExternalLrcProvider) {
            configure()
            if (trackName.isNullOrBlank()) return@synchronized null
            val now = System.nanoTime() / 1_000_000
            if (indexedAt == 0L || now - indexedAt >= 30_000) {
                val seen = mutableSetOf<String>()
                val values = mutableListOf<Pair<File, LyricsResult>>()
                RuntimeBridge.settings.folders.filter { it.uri.startsWith("/") }.forEach { folder ->
                    File(folder.uri).takeIf { it.isDirectory }?.walkTopDown()?.maxDepth(3)?.take(5000)?.forEach { file ->
                        if (file.isFile && file.extension.equals("lrc", true) && seen.add(file.absolutePath)) read(file)?.let { values += file to it }
                    }
                }
                // Drop removed files from the parsed fallback index.
                parsed.retain(seen)
                index = values.filter { !it.second.trackName.isNullOrBlank() }.groupBy { normalize(it.second.trackName) }.mapValues { (_, rows) -> rows.map { it.first } }
                indexedAt = now
            }
            index[normalize(trackName)].orEmpty().filter { it.isFile }.mapNotNull(::read).filter {
                normalize(it.trackName) == normalize(trackName) && (
                artistName.isNullOrBlank() || it.artistName.isNullOrBlank() || normalize(it.artistName) == normalize(artistName))
            }.singleOrNull()
        }
    }
}
