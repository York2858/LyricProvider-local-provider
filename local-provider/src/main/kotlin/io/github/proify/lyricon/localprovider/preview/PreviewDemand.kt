package io.github.proify.lyricon.localprovider.preview

/** A crashed/abandoned preview automatically loses its subscription. Uses elapsed time. */
class PreviewDemand(private val leaseMs: Long = 30_000) {
    private val owners = mutableMapOf<String, Long>()
    @Synchronized fun update(owner: String, active: Boolean, now: Long): Long {
        owners.entries.removeAll { it.value <= now }
        if (active) owners[owner] = now + leaseMs else owners.remove(owner)
        return owners.values.maxOrNull() ?: 0
    }
    @Synchronized fun expiresAt(now: Long): Long = owners.values.filter { it > now }.maxOrNull() ?: 0
}
