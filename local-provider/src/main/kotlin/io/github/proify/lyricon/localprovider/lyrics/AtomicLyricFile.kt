package io.github.proify.lyricon.localprovider.lyrics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Write and verify in the same directory; never truncate the destination before success. */
object AtomicLyricFile {
    private const val MAX_BYTES = 2_000_000
    private val metadata = Regex("^\\[(?:ti|ar|al|by|offset|re|ve|length|au|tool|id|encoding|language|la):.*]$", RegexOption.IGNORE_CASE)
    private val timestamp = Regex("(?:\\[|<)\\d+:\\d{1,2}(?:[.:]\\d{1,3})?(?:]|>)")
    private val roles = Regex("^(?:(?:v\\d+|bg|background):\\s*)+", RegexOption.IGNORE_CASE)
    fun clearlyInvalid(raw: String): Boolean = raw.removePrefix("\uFEFF").lineSequence().all { row ->
        val value = row.trim()
        value.isEmpty() || metadata.matches(value) || timestamp.replace(value, "").trim().replace(roles, "").isBlank()
    }
    private fun keepExisting(target: File, overwrite: Boolean): Boolean {
        if (!target.exists()) return false
        if (!target.isFile) return true
        if (overwrite) return false
        // Unknown formats, large files, and unreadable files are conservatively preserved.
        if (target.length() > MAX_BYTES) return true
        return !clearlyInvalid(target.readText(Charsets.UTF_8))
    }
    @Synchronized fun save(target: File, content: String, overwrite: Boolean,
                           writeTemporary: (File, ByteArray) -> Unit = ::writeSynced): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || clearlyInvalid(content)) return false
        if (keepExisting(target, overwrite)) return false
        val directory = target.absoluteFile.parentFile ?: return false
        val temporary = File.createTempFile(".lyricon-", ".tmp", directory)
        try {
            writeTemporary(temporary, bytes)
            if (!temporary.readBytes().contentEquals(bytes)) throw IOException("Incomplete lyric write")
            // Another request may have produced valid lyrics while this temporary file was written.
            if (keepExisting(target, overwrite)) return false
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            temporary.delete()
        }
    }
    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }
}
