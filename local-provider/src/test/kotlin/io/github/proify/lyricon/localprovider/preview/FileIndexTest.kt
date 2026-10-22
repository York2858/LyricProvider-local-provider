package io.github.proify.lyricon.localprovider.preview
import io.github.proify.lyricon.localprovider.data.FileIndex
import org.junit.Assert.*
import org.junit.Test
class FileIndexTest {
    @Test fun reusesUnchangedFilesAndReparsesEditsDeletesAndRuleChanges() {
        val index = FileIndex<String>(); var reads = 0
        fun read(mtime: Long = 1, size: Long = 20) = index.read("song", mtime, size) { "read${++reads}" }
        assertEquals("read1", read()); assertEquals("read1", read())
        assertEquals("read2", read(size = 21)); assertEquals("read3", read(mtime = 2))
        index.retain(emptySet()); assertEquals("read4", read())
        index.clear(); assertEquals("read5", read())
        assertEquals("read6", read(mtime = 0)); assertEquals("read7", read(mtime = 0))
    }
    @Test fun invalidLyricsAreCachedUntilTheirFileChanges() {
        val index = FileIndex<String>(); var reads = 0
        repeat(3) { assertNull(index.read("bad", 1, 20) { reads++; null }) }
        assertEquals(1, reads)
        assertEquals("fixed", index.read("bad", 2, 20) { "fixed" })
    }
}
