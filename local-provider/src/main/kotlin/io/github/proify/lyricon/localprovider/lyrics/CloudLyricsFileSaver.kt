package io.github.proify.lyricon.localprovider.lyrics

import androidx.annotation.Keep
import io.github.proify.lyricon.localprovider.model.LyricsResult
import java.io.File

/** Stable entry point for verifying the actual R8-processed exporter in device tests. */
@Keep
object CloudLyricsFileSaver {
    @JvmStatic fun save(audioFile: File, result: LyricsResult, overwrite: Boolean): Boolean {
        if (!audioFile.isFile) return false
        val content = EnhancedLrcEncoder.encode(result) ?: return false
        val parent = audioFile.absoluteFile.parentFile ?: return false
        return AtomicLyricFile.save(File(parent, "${audioFile.nameWithoutExtension}.lrc"), content, overwrite)
    }
}
