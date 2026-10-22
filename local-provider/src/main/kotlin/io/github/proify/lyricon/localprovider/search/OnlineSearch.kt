package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogAccessException
import io.github.proify.cloudlyric.LyricsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

data class SearchBatch(val provider: String, val results: List<SearchCandidate>, val error: String? = null)

/** One query session: independent source cursors, shared downloads, and bounded detection. */
class OnlineSearch(parent: CoroutineScope? = null) {
    private val scope = CoroutineScope(SupervisorJob(parent?.coroutineContext?.get(Job)) + Dispatchers.IO)
    private data class Cursor(var value: String? = null, var exhausted: Boolean = false, val seen: MutableSet<String> = mutableSetOf())
    private val cursors = ConcurrentHashMap<String, Cursor>()
    private val sources = ConcurrentHashMap<String, CatalogProvider>()
    private data class Download(val request: Deferred<SearchCandidate>, var users: Int = 0)
    private val downloads = mutableMapOf<String, Download>()
    private val limiter = Semaphore(3)
    private data class Detection(val item: SearchCandidate, val update: suspend (SearchCandidate) -> Unit)
    private class DetectionRun(parent: Job?) {
        val job = SupervisorJob(parent)
        val queue = Channel<Detection>(64)
        val queued = mutableSetOf<String>()
    }
    private var detectionEnabled = true
    private var detection: DetectionRun? = null
    private var activeQuery = ""
    @Synchronized fun pauseDetection() {
        detectionEnabled = false
        detection?.let { it.queue.cancel(); it.job.cancel() }
        detection = null
    }
    @Synchronized fun resumeDetection() { detectionEnabled = true }
    fun close() { pauseDetection(); scope.cancel() }
    @Synchronized private fun detectionRun(): DetectionRun? {
        if (!detectionEnabled || !scope.isActive) return null
        detection?.let { return it }
        val run = DetectionRun(scope.coroutineContext[Job])
        detection = run
        repeat(3) {
            CoroutineScope(scope.coroutineContext + run.job).launch {
                for (task in run.queue) {
                    try {
                        ensureActive()
                        task.update(task.item.copy(lyricState = LyricState.CHECKING))
                        val value = resolve(task.item)
                        ensureActive(); task.update(value)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    finally { synchronized(this@OnlineSearch) { run.queued.remove(task.item.id) } }
                }
            }
        }
        return run
    }
    fun canLoadMore(providerIds: Collection<String>): Boolean = providerIds.any { cursors[it]?.exhausted != true }

    suspend fun search(providers: List<LyricsProvider>, query: String, limit: Int,
                       more: Boolean = false, onBatch: suspend (SearchBatch) -> Unit) = coroutineScope {
        require(query.isNotBlank())
        if (activeQuery != query || !more) {
            cursors.clear(); activeQuery = query
            synchronized(downloads) { downloads.values.forEach { it.request.cancel() }; downloads.clear() }
        }
        providers.forEach { source ->
            launch(Dispatchers.IO) {
                val cursor = cursors.getOrPut(source.id) { Cursor() }
                var commit: (() -> Unit)? = null
                val batch = try {
                    val catalog = source as? CatalogProvider ?: error("此来源尚不支持歌曲搜索")
                    val owner = sources.putIfAbsent(source.id, catalog) ?: catalog
                    if (more && cursor.exhausted) SearchBatch(source.id, emptyList()) else {
                        val requestCursor = cursor.value
                        val page = withTimeout(30_000) { owner.searchCatalog(query, limit.coerceIn(1, 10), requestCursor) }
                        currentCoroutineContext().ensureActive()
                        val fresh = page.tracks.distinctBy { it.key }.filter { it.key !in cursor.seen }
                        commit = {
                            cursor.seen.addAll(fresh.map { it.key })
                            cursor.value = page.nextCursor
                            cursor.exhausted = page.nextCursor == null || page.nextCursor == requestCursor || fresh.isEmpty()
                        }
                        SearchBatch(source.id, fresh.map { SearchCandidate.catalog(source.id, it) })
                    }
                } catch (_: TimeoutCancellationException) { SearchBatch(source.id, emptyList(), "搜索超时，点击重试") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: CatalogAccessException) { SearchBatch(source.id, emptyList(), error.message) }
                catch (_: Exception) { SearchBatch(source.id, emptyList(), "搜索失败，点击重试") }
                onBatch(batch)
                currentCoroutineContext().ensureActive()
                commit?.invoke()
            }
        }
    }

    @Synchronized fun detect(candidates: List<SearchCandidate>, onUpdate: suspend (SearchCandidate) -> Unit) {
        val run = detectionRun() ?: return
        candidates.filter { it.remote != null && it.lyricState == LyricState.UNKNOWN }.forEach { item ->
            if (run.queued.add(item.id) && run.queue.trySend(Detection(item, onUpdate)).isFailure) run.queued.remove(item.id)
        }
    }

    suspend fun resolve(item: SearchCandidate, retry: Boolean = false): SearchCandidate {
        if (item.remote == null || item.lyricState == LyricState.READY) return item
        val request = synchronized(downloads) {
            if ((retry && downloads[item.id]?.request?.isCompleted == true) || downloads[item.id]?.request?.isCancelled == true) downloads.remove(item.id)
            downloads.getOrPut(item.id) {
                Download(scope.async(start = CoroutineStart.LAZY) {
                    limiter.withPermit {
                        try {
                            val provider = sources[item.source] ?: error("请重新搜索")
                            val lyrics = withTimeout(45_000) { provider.loadLyrics(item.remote) }
                            currentCoroutineContext().ensureActive()
                            if (lyrics == null || lyrics.rich.isEmpty()) item.copy(lyricState = LyricState.NONE)
                            else item.copy(song = SearchCandidate.online(item.source, lyrics).song,
                                artworkUrl = lyrics.artworkUrl.ifBlank { item.artworkUrl }, lyricState = LyricState.READY)
                        } catch (_: TimeoutCancellationException) { item.copy(lyricState = LyricState.ERROR) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { item.copy(lyricState = LyricState.ERROR) }
                    }
                })
            }.also { it.users++ }
        }
        try {
            request.request.start()
            return request.request.await()
        } finally {
            synchronized(downloads) {
                request.users--
                if (request.users == 0 && !request.request.isCompleted) {
                    request.request.cancel()
                    if (downloads[item.id] === request) downloads.remove(item.id)
                }
            }
        }
    }
}
