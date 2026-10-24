package io.github.proify.lyricon.localprovider.preview

import io.github.proify.lyricon.localprovider.data.LocalScan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalScanTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun includesLyricsAndMusicInsideSixNestedDirectoriesButStopsBeforeSeven() {
        val root = temporary.newFolder()
        File(root, "root.lrc").writeText("root")
        var folder = root
        repeat(7) { index ->
            folder = File(folder, "level${index + 1}").apply { mkdir() }
            File(folder, "song${index + 1}.lrc").writeText("lyrics")
            File(folder, "song${index + 1}.flac").writeText("audio")
        }
        val found = LocalScan.files(root).map { it.name }.toSet()
        assertTrue(found.containsAll(listOf("root.lrc", "song3.lrc", "song6.lrc", "song6.flac")))
        assertFalse(found.contains("song7.lrc"))
        assertFalse(found.contains("song7.flac"))
    }
    @Test fun recognizesAudioExtensionsWithoutMistakingLyricFilesForMusic() {
        listOf("song.FLAC", "song.mp3", "song.m4a", "song.opus", "song.ogg").forEach { assertTrue(LocalScan.isAudio(it)) }
        listOf("song.lrc", "song.ttml", "cover.jpg", "notes").forEach { assertFalse(LocalScan.isAudio(it)) }
    }
}
