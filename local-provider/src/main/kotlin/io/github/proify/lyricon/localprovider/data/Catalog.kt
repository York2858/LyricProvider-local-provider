package io.github.proify.lyricon.localprovider.data

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer
import io.github.proify.lyricon.lyric.model.Song
import java.io.File
import java.util.Locale

data class LyricFile(
    val uri: String, val filename: String, val directory: String, val modified: Long,
    val song: Song
) {
    val wordLevel: Boolean get() = LyricTiming.hasWordTiming(song.lyrics.orEmpty())
    val romanization: Boolean get() = song.lyrics.orEmpty().any { !it.roma.isNullOrBlank() }
    val translated: Boolean get() = song.lyrics.orEmpty().any { !it.translation.isNullOrBlank() }
}

/** Reads only configured directories; SAF access remains in the module's process. */
object Catalog {
    private var cached: List<LyricFile>? = null
    private var cachedAt = 0L
    private var byUri = emptyMap<String, LyricFile>()
    private var byTitle = emptyMap<String, List<LyricFile>>()
    private var byBasename = emptyMap<String, List<LyricFile>>()
    private val parsed = FileIndex<LyricFile>()
    private var parserRules: Pair<List<String>, List<String>>? = null
    private val seen = mutableSetOf<String>()
    private fun indexed(uri: String, name: String, directory: String, modified: Long, size: Long,
                        read: () -> String?): LyricFile? {
        seen += uri
        return parsed.read(uri, modified, size) {
            read()?.takeIf { it.length <= 2_000_000 }?.let { parse(uri, name, directory, modified, it) }
        }?.copy(filename = name, directory = directory)
    }
    @Synchronized fun invalidate() { cached = null }
    @Synchronized fun scan(context: Context, force: Boolean = false): List<LyricFile> {
        if (!force && cached != null && android.os.SystemClock.elapsedRealtime() - cachedAt < 30_000) return cached!!
        val settings = ProviderStore(context).settings()
        val rules = settings.exactRules.toList() to settings.keywordRules.toList()
        if (force || rules != parserRules) parsed.clear()
        parserRules = rules
        seen.clear()
        EnhanceLrcParser.customStaffRoleExact.apply { clear(); addAll(settings.exactRules) }
        EnhanceLrcParser.customStaffRoleKeywords.apply { clear(); addAll(settings.keywordRules) }
        val entries = linkedMapOf<String, LyricFile>()
        SavedLyrics(context).files().forEach { entries[it.uri] = it }
        settings.folders.forEach { folder ->
            runCatching {
                if (folder.uri.startsWith("content:")) {
                    val tree = Uri.parse(folder.uri)
                    walkTree(context, tree, DocumentsContract.getTreeDocumentId(tree), folder.name, 0, entries)
                } else {
                    File(folder.uri).takeIf { it.isDirectory && it.canRead() }?.walkTopDown()?.maxDepth(3)
                        ?.take(5000)?.filter { it.isFile && supported(it.name) }
                        ?.forEach { file ->
                            if (file.length() <= 2_000_000) indexed(file.toURI().toString(), file.name,
                                file.parentFile?.name.orEmpty(), file.lastModified(), file.length()) { file.readText() }
                                ?.let { entries[it.uri] = it }
                        }
                }
            }
        }
        settings.files.forEach { entry ->
            runCatching {
                val uri = Uri.parse(entry.uri)
                var modified = 0L; var size = -1L
                runCatching {
                    context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { modified = c.getLong(0); size = c.getLong(1) }
                    }
                }
                if (size <= 2_000_000) indexed(entry.uri, entry.name, "导入的歌词", modified, size) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }?.let { entries[it.uri] = it }
            }
        }
        parsed.retain(seen)
        cached = entries.values.toList()
        byUri = entries.toMap()
        byTitle = cached!!.groupBy { comparable(it.song.name) }
        byBasename = cached!!.groupBy { it.filename.substringBeforeLast('.').lowercase(Locale.ROOT) }
        cachedAt = android.os.SystemClock.elapsedRealtime()
        return cached!!
    }
    private fun walkTree(context: Context, tree: Uri, docId: String, directory: String, depth: Int,
                         result: MutableMap<String, LyricFile>) {
        if (depth > 3 || result.size >= 5000) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 5000) {
                val id = cursor.getString(0); val name = cursor.getString(1).orEmpty()
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(context, tree, id, "$directory/$name", depth + 1, result)
                } else if (supported(name) && cursor.getLong(4) <= 2_000_000) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    runCatching {
                        indexed(uri.toString(), name, directory, cursor.getLong(3), cursor.getLong(4)) {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }?.let { result[it.uri] = it }
                    }
                }
            }
        }
    }
    internal fun parse(uri: String, name: String, directory: String, modified: Long, raw: String): LyricFile? {
        if (name.endsWith(".lrc", true) && io.github.proify.lyricon.localprovider.lyrics.AtomicLyricFile.clearlyInvalid(raw)) return null
        val doc = EnhanceLrcParser.parse(if (name.endsWith(".ttml", true)) "" else raw)
        val parsed = if (name.endsWith(".ttml", true))
            io.github.proify.cloudlyric.provider.apple.AppleMusicTtmlParser.parse(raw) else doc.lines
        val lines = LyricNormalizer.normalizeForTripleLine(parsed)
        if (lines.isEmpty()) return null
        return LyricFile(uri, name, directory, modified, Song(
            name = doc.metadata["ti"]?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
            artist = doc.metadata["ar"], lyrics = lines, duration = lines.lastOrNull()?.end ?: 0
        ))
    }
    /** Exact filename/title matching only. Ambiguous matches wait for manual selection. */
    @Synchronized fun find(context: Context, title: String?, artist: String?, audioPath: String?): LyricFile? {
        if (title.isNullOrBlank()) return null
        scan(context)
        val choice = ProviderStore(context).choices()[trackKey(title, artist)]
        choice?.uri?.takeIf { it.isNotBlank() }?.let { selected ->
            SavedLyrics(context).get(selected)?.let { saved ->
                return applyChoice(LyricFile(selected, saved.song.name.orEmpty(), saved.source,
                    saved.savedAt, saved.song), choice)
            }
            byUri[selected]?.let { return applyChoice(it, choice) }
        }
        val basename = audioPath?.let { File(it).nameWithoutExtension }
        val siblings = byBasename[basename?.lowercase(Locale.ROOT)].orEmpty().filter { file ->
            basename != null && file.filename.substringBeforeLast('.').equals(basename, true)
                && sameDirectory(file.uri, audioPath)
        }
        val candidates = if (siblings.isNotEmpty()) siblings else byTitle[comparable(title)].orEmpty().filter {
            comparable(it.song.name) == comparable(title) &&
                (artist.isNullOrBlank() || it.song.artist.isNullOrBlank() || comparable(it.song.artist) == comparable(artist))
        }
        return candidates.singleOrNull()?.let { applyChoice(it, choice ?: SongChoice()) }
    }
    private fun sameDirectory(uri: String, audioPath: String?): Boolean {
        if (audioPath == null) return false
        val parent = File(audioPath).parent.orEmpty()
        return if (uri.startsWith("file:")) File(Uri.parse(uri).path.orEmpty()).parent == parent
        else runCatching {
            val id = DocumentsContract.getDocumentId(Uri.parse(uri))
            val relativeParent = id.substringAfter(':').substringBeforeLast('/', "")
            parent.trimEnd('/').endsWith("/$relativeParent")
        }.getOrDefault(false)
    }
    private fun applyChoice(file: LyricFile, choice: SongChoice): LyricFile = if (choice.offset == 0L) file else
        file.copy(song = file.song.copy(lyrics = file.song.lyrics?.map { line ->
            line.copy(begin = (line.begin + choice.offset).coerceAtLeast(0),
                end = (line.end + choice.offset).coerceAtLeast(0),
                words = line.words?.map { word -> word.copy(begin = (word.begin + choice.offset).coerceAtLeast(0),
                    end = (word.end + choice.offset).coerceAtLeast(0)) },
                secondaryWords = line.secondaryWords?.map { word -> word.copy(
                    begin = (word.begin + choice.offset).coerceAtLeast(0), end = (word.end + choice.offset).coerceAtLeast(0)) },
                translationWords = line.translationWords?.map { word -> word.copy(
                    begin = (word.begin + choice.offset).coerceAtLeast(0), end = (word.end + choice.offset).coerceAtLeast(0)) })
        }))
    private fun comparable(text: String?): String = text.orEmpty().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun supported(name: String) = name.endsWith(".lrc", true) || name.endsWith(".ttml", true)
}
