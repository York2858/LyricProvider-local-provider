package io.github.proify.lyricon.localprovider.data

import android.content.Context
import android.net.Uri
import com.kyant.taglib.TagLib
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/** Reads metadata only, without loading an entire audio file or its cover into memory. */
object EmbeddedCatalog {
    fun read(context: Context, uri: Uri, name: String, directory: String, modified: Long): LyricFile? = runCatching {
        val properties = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            TagLib.getMetadata(it.detachFd(), false)?.propertyMap
        } ?: return null
        parse(uri.toString(), name, directory, modified, properties)
    }.getOrNull()

    internal fun parse(uri: String, name: String, directory: String, modified: Long,
                       properties: Map<String, Array<String>>): LyricFile? {
        fun tag(key: String) = properties.entries.firstOrNull { it.key.equals(key, true) }
            ?.value?.firstOrNull { it.isNotBlank() }
        // Prefer synchronized/enhanced lyrics when several tags are present.
        val lyrics = properties.entries.filter { it.key.matches(Regex("(?i)(LYRICS\\d*|USLT|SYLT)")) }
            .flatMap { it.value.toList() }.filter { it.isNotBlank() && it.length <= 2_000_000 }
            .mapNotNull { raw ->
                Catalog.parse(uri, "${name.substringBeforeLast('.')}.lrc", directory, modified, raw)
                    ?: if (Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(raw) || raw.trimStart().startsWith('<')) null
                    else raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                        .filterNot { it.matches(Regex("\\[[A-Za-z]+:.*]")) }
                        .map { RichLyricLine(text = it) }.toList().takeIf { it.isNotEmpty() }
                        ?.let { LyricFile(uri, name, directory, modified, Song(name = name.substringBeforeLast('.'), lyrics = it)) }
            }
            .maxByOrNull { file -> when {
                file.wordLevel -> 2
                file.song.lyrics.orEmpty().any { it.begin > 0 || it.end > it.begin } -> 1
                else -> 0
            } } ?: return null
        return lyrics.copy(filename = name, embedded = true, album = tag("ALBUM").orEmpty(),
            song = lyrics.song.copy(name = tag("TITLE") ?: lyrics.song.name, artist = tag("ARTIST") ?: lyrics.song.artist,
                duration = 0)) // A final lyric timestamp is not the audio duration.
    }
}
