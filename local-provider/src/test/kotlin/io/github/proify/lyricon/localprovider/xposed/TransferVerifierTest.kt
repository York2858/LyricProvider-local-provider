package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class TransferVerifierTest {
    private val line = RichLyricLine(begin = 1000, text = "こんにちは", roma = "konnichiwa", translation = "你好")
    private val song = Song(name = "Song", artist = "Artist", lyrics = listOf(line))
    private val sent = RuntimeSnapshot(player = "test.player", song = song)
    @Test fun receivedCanonicalFieldsAreConfirmed() {
        assertTrue(TransferVerifier.compare(sent, song, TransferVerifier.PACKAGE, "test.player").startsWith("字段一致"))
    }
    @Test fun droppedRomanizationIsReportedEvenWhenTranslationSurvives() {
        val received = song.copy(lyrics = listOf(line.copy(roma = null)))
        assertTrue(TransferVerifier.compare(sent, received, TransferVerifier.PACKAGE, "test.player").startsWith("字段不一致"))
    }
    @Test fun swappedAuxiliaryFieldsAreNotAccepted() {
        val received = song.copy(lyrics = listOf(line.copy(roma = line.translation, translation = line.roma)))
        assertTrue(TransferVerifier.compare(sent, received, TransferVerifier.PACKAGE, "test.player").startsWith("字段不一致"))
    }
    @Test fun nativePlayerOutputIsNotMistakenForPluginOutput() {
        assertTrue(TransferVerifier.compare(sent, song, "native.player", "test.player").contains("其他来源"))
    }
    @Test fun anotherTrackCannotPassTheComparison() {
        assertTrue(TransferVerifier.compare(sent, song.copy(name = "Another song"), TransferVerifier.PACKAGE, "test.player").contains("歌曲不一致"))
    }
}
