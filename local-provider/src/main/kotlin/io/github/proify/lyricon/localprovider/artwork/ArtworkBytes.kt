package io.github.proify.lyricon.localprovider.artwork

import java.io.ByteArrayOutputStream
import java.io.InputStream

object ArtworkBytes {
    const val MAX_DOWNLOAD = 4_000_000
    fun read(input: InputStream, limit: Int = MAX_DOWNLOAD): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "Artwork exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
