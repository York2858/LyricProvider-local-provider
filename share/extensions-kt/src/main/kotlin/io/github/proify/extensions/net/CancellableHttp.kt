package io.github.proify.extensions.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the socket even while a response body is being read. */
suspend fun Call.awaitText(requireSuccess: Boolean = false): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    if (requireSuccess && !it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body.string()
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
