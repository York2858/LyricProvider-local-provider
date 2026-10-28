package io.github.proify.lyricon.localprovider.artwork

import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import io.github.proify.lyricon.localprovider.R
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.localprovider.ui.ProviderIcon
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object ArtworkLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failedUntil = LruCache<String, Long>(200)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    fun bind(view: ImageView, source: String, scaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP) {
        if (view.getTag(R.id.provider_artwork_request) == source) return
        view.setTag(R.id.provider_artwork_request, source)
        val padding = (view.resources.displayMetrics.density * 16).toInt()
        view.setPadding(padding, padding, padding, padding); view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setImageDrawable(ProviderIcon("music", view.context.getColor(R.color.provider_muted)))
        if (source.isBlank()) return
        val key = SearchCandidate.digest(source)
        fun show(bitmap: Bitmap) { view.setPadding(0, 0, 0, 0); view.scaleType = scaleType; view.setImageBitmap(bitmap) }
        cache.get(key)?.let { show(it); return }
        if ((failedUntil.get(key) ?: 0L) > android.os.SystemClock.elapsedRealtime()) return
        val context = view.context.applicationContext
        val reference = WeakReference(view)
        scope.launch {
            val pending = CompletableDeferred<Bitmap?>()
            val previous = inFlight.putIfAbsent(key, pending)
            val bitmap = if (previous != null) previous.await() else {
                val loaded = runCatching {
                    val folder = File(context.cacheDir, "artwork-thumbnails").apply { mkdirs() }
                    val disk = File(folder, "$key.jpg")
                    val result = if (disk.isFile) ArtworkCapture.decode(disk.readBytes()) else null
                    result ?: fetch(source)?.let { original ->
                        val small = ArtworkCapture.thumbnail(original)
                        if (small !== original) original.recycle()
                        val output = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 85, output)
                        runCatching { android.util.AtomicFile(disk).let { atomic ->
                            val stream = atomic.startWrite()
                            try { stream.write(output.toByteArray()); atomic.finishWrite(stream) }
                            catch (error: Exception) { atomic.failWrite(stream) }
                        } }
                        folder.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedByDescending { it.lastModified() }
                            .drop(200).forEach { it.delete() }
                        small
                    }
                }.getOrNull()
                if (loaded != null) { cache.put(key, loaded); failedUntil.remove(key) }
                else failedUntil.put(key, android.os.SystemClock.elapsedRealtime() + 30_000)
                pending.complete(loaded); inFlight.remove(key, pending)
                loaded
            }
            if (bitmap != null) withContext(Dispatchers.Main.immediate) {
                reference.get()?.takeIf { it.getTag(R.id.provider_artwork_request) == source }?.let { target ->
                    target.setPadding(0, 0, 0, 0); target.scaleType = scaleType; target.setImageBitmap(bitmap)
                }
            }
        }
    }
    private fun fetch(source: String): Bitmap? {
        val bytes = if (source.startsWith("base64:")) {
            if (source.length > 65000) return null
            Base64.decode(source.removePrefix("base64:"), Base64.DEFAULT)
        } else {
            val url = io.github.proify.cloudlyric.CoverUrls.normalize(source)
            if (url.isBlank()) return null
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                if (connection.responseCode != 200) return null
                if (connection.contentLengthLong > ArtworkBytes.MAX_DOWNLOAD) return null
                connection.inputStream.use { ArtworkBytes.read(it) }
            } finally { connection.disconnect() }
        }
        return ArtworkCapture.decode(bytes)
    }
}
