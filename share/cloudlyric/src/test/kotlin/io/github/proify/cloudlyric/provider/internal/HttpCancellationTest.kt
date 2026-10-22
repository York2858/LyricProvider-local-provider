package io.github.proify.cloudlyric.provider.internal
import io.github.proify.extensions.net.awaitText
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*
class HttpCancellationTest {
    @Test fun normalResponseAndCancelledStalledBodyReleaseTheCall() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { e -> e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("ok".toByteArray()) } }
        server.createContext("/stall") { e ->
            e.sendResponseHeaders(200, 100); e.responseBody.write(1); e.responseBody.flush()
            started.complete(Unit); release.await(5, TimeUnit.SECONDS); e.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("ok", ProviderSupport.httpGet("$base/ok"))
            val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("$base/stall").build())
            val request = async { call.awaitText() }
            withTimeout(3000) { started.await() }
            withTimeout(1000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled); assertTrue(call.isCanceled())
        } finally { release.countDown(); server.stop(0) }
    }
    @Test fun timeoutRemainsCancellationAndCancelsTheStalledNetworkCall() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stall") { e ->
            e.sendResponseHeaders(200, 100); e.responseBody.write(1); e.responseBody.flush()
            started.complete(Unit); release.await(5, TimeUnit.SECONDS); e.close()
        }
        server.start()
        val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
            .url("http://127.0.0.1:${server.address.port}/stall").build())
        try {
            val outcome = async { runCatching { withTimeout(1000) { call.awaitText() } } }
            withTimeout(3000) { started.await() }
            assertTrue(outcome.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(call.isCanceled())
        } finally { release.countDown(); server.stop(0) }
    }
}
