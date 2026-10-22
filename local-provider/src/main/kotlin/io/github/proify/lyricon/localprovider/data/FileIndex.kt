package io.github.proify.lyricon.localprovider.data

/** Reuses parsed content, including invalid files. Unknown timestamps are never trusted. */
class FileIndex<T> {
    private data class Entry<T>(val modified: Long, val size: Long, val value: T?)
    private val entries = mutableMapOf<String, Entry<T>>()
    @Synchronized fun read(key: String, modified: Long, size: Long, load: () -> T?): T? {
        val old = entries[key]
        if (modified > 0 && size >= 0 && old?.modified == modified && old.size == size) return old.value
        val value = load()
        entries[key] = Entry(modified, size, value)
        return value
    }
    @Synchronized fun retain(keys: Set<String>) { entries.keys.retainAll(keys) }
    @Synchronized fun forget(key: String) { entries.remove(key) }
    @Synchronized fun clear() { entries.clear() }
}
