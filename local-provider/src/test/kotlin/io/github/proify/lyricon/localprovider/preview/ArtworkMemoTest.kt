package io.github.proify.lyricon.localprovider.preview
import io.github.proify.lyricon.localprovider.artwork.ArtworkMemo
import org.junit.Assert.*
import org.junit.Test
class ArtworkMemoTest {
    @Test fun missingArtIsNotRepeatedlyDecodedButLaterBitmapOrPathIsImmediate() {
        val memo = ArtworkMemo(100); var reads = 0
        repeat(10) { assertNull(memo.load("missing", it.toLong()) { reads++; null }) }
        assertEquals(1, reads)
        assertEquals("cover", memo.load("new bitmap", 10) { reads++; "cover" })
        assertEquals("cover", memo.load("new bitmap", 11) { error("same bitmap must be reused") })
        assertEquals("new cover", memo.load("new path", 12) { "new cover" })
        assertNull(memo.load("missing", 20) { reads++; null })
        assertNull(memo.load("missing", 120) { reads++; null })
        assertEquals(4, reads)
    }
}
