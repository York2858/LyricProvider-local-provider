package io.github.proify.lyricon.localprovider.data

import java.io.File

/** The configured root is depth zero; files in six nested directories are included. */
object LocalScan {
    const val MAX_DIRECTORY_DEPTH = 6
    const val MAX_ENTRIES = 5000
    fun files(root: File): Sequence<File> = root.walkTopDown()
        .maxDepth(MAX_DIRECTORY_DEPTH + 1).take(MAX_ENTRIES).filter { it.isFile }
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in
        setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav", "aiff", "aif", "ape", "wv", "wma")
}
