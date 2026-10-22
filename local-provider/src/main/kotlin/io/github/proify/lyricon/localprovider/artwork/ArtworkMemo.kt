package io.github.proify.lyricon.localprovider.artwork

/** Failed extraction is briefly cached; a changed source can always be read immediately. */
class ArtworkMemo(private val retryMs: Long = 10_000) {
    private var lastKey: String? = null
    private var lastValue: String? = null
    private var checkedAt = 0L
    @Synchronized fun load(key: String, now: Long, read: () -> String?): String? {
        if (key == lastKey && (lastValue != null || now - checkedAt in 0 until retryMs)) return lastValue
        val value = read()
        lastKey = key; lastValue = value; checkedAt = now
        return value
    }
}
