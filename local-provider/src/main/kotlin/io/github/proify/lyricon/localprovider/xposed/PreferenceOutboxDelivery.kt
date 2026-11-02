package io.github.proify.lyricon.localprovider.xposed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** One event-triggered delivery round. Only acknowledged entries leave durable storage. */
internal suspend fun deliverPreferenceOutbox(
    pending: () -> List<Pair<String, String>>,
    send: (String) -> Unit,
    acknowledge: (String) -> Unit,
    wakeModule: () -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    val retryDelays = longArrayOf(1_000L, 2_000L, 4_000L)
    var retries = 0
    var wakeRequested = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val batch = pending()
        if (batch.isEmpty()) return
        try {
            for ((key, raw) in batch) {
                currentCoroutineContext().ensureActive()
                send(raw)
                acknowledge(key)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Leave the remaining entries on disk; a later event starts a fresh round.
            val retryDelay = retryDelays.getOrNull(retries++) ?: return
            if (!wakeRequested) {
                wakeRequested = true
                runCatching { wakeModule() }
            }
            waitBeforeRetry(retryDelay)
        }
    }
}
