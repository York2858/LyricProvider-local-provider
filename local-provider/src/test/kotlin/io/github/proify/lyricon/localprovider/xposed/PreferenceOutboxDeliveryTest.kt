package io.github.proify.lyricon.localprovider.xposed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreferenceOutboxDeliveryTest {
    @Test fun failedDeliveryStopsAfterThreeRetriesAndRetainsTheRecord() = runBlocking<Unit> {
        val outbox = linkedMapOf("record" to "rating=100")
        val waits = mutableListOf<Long>()
        var attempts = 0
        var wakes = 0
        deliverPreferenceOutbox(
            pending = { outbox.toList() },
            send = { attempts++; error("Module unavailable") },
            acknowledge = { outbox.remove(it) },
            wakeModule = { wakes++ },
            waitBeforeRetry = { waits += it },
        )
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
        assertEquals(1, wakes)
        assertEquals(mapOf("record" to "rating=100"), outbox)

        // A later player event or player restart can deliver the retained record.
        val delivered = mutableListOf<String>()
        deliverPreferenceOutbox(
            pending = { outbox.toList() },
            send = { delivered += it },
            acknowledge = { outbox.remove(it) },
            wakeModule = { fail("Successful delivery must not wake the module") },
            waitBeforeRetry = { fail("Successful delivery must not schedule a retry") },
        )
        assertEquals(listOf("rating=100"), delivered)
        assertTrue(outbox.isEmpty())
    }

    @Test fun partialSuccessOnlyRemovesAcknowledgedRecords() = runBlocking<Unit> {
        val outbox = linkedMapOf("first" to "favorite=true", "second" to "rating=80")
        val attempts = mutableListOf<String>()
        deliverPreferenceOutbox(
            pending = { outbox.toList() },
            send = { raw -> attempts += raw; if (raw == "rating=80") error("Unavailable") },
            acknowledge = { outbox.remove(it) },
            wakeModule = {},
            waitBeforeRetry = {},
        )
        assertEquals(1, attempts.count { it == "favorite=true" })
        assertEquals(4, attempts.count { it == "rating=80" })
        assertEquals(mapOf("second" to "rating=80"), outbox)
    }

    @Test fun recoveryStopsRetryingAndEmptiesTheOutbox() = runBlocking<Unit> {
        val outbox = linkedMapOf("record" to "rating=60")
        val waits = mutableListOf<Long>()
        var attempts = 0
        deliverPreferenceOutbox(
            pending = { outbox.toList() },
            send = { if (++attempts < 3) error("Temporarily unavailable") },
            acknowledge = { outbox.remove(it) },
            wakeModule = {},
            waitBeforeRetry = { waits += it },
        )
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), waits)
        assertTrue(outbox.isEmpty())
    }

    @Test fun cancellationRetainsRecordsWithoutRetrying() = runBlocking<Unit> {
        val outbox = linkedMapOf("record" to "rating=100")
        try {
            deliverPreferenceOutbox(
                pending = { outbox.toList() },
                send = { throw CancellationException("Player stopped") },
                acknowledge = { fail("Cancelled records must remain") },
                wakeModule = { fail("Cancellation must not wake the module") },
                waitBeforeRetry = { fail("Cancellation must not schedule retries") },
            )
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(mapOf("record" to "rating=100"), outbox)
        }
    }
}
