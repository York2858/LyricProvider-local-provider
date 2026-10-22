package io.github.proify.lyricon.localprovider.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import io.github.proify.cloudlyric.CoverUrls
import java.io.ByteArrayOutputStream

/** Runs in the player process, where private artwork content URIs can actually be read. */
object ArtworkCapture {
    private val memo = ArtworkMemo()
    @Synchronized fun capture(context: Context, metadata: MediaMetadata, audioPath: String?): String? = runCatching {
        val bitmap = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON).firstNotNullOfOrNull { key -> runCatching { metadata.getBitmap(key) }.getOrNull() }
        val uri = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).firstNotNullOfOrNull { key -> metadata.getString(key)?.takeIf { it.isNotBlank() } }
        val key = listOf(metadata.getString(MediaMetadata.METADATA_KEY_TITLE), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            bitmap?.let { "${System.identityHashCode(it)}:${it.generationId}" }, uri, audioPath,
            audioPath?.let { java.io.File(it).lastModified() }).joinToString("|")
        memo.load(key, android.os.SystemClock.elapsedRealtime()) { runCatching {
        when {
            bitmap != null -> encode(bitmap)
            !CoverUrls.normalize(uri).isBlank() -> CoverUrls.normalize(uri)
            uri != null -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { decode(ArtworkBytes.read(it)) }?.let(::encode)
            }.getOrNull()
            else -> null
        } ?: audioPath?.takeIf { it.startsWith('/') }?.let { path ->
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(path); retriever.embeddedPicture?.let(::decode)?.let(::encode) }
            finally { runCatching { retriever.release() } }
        }
        }.getOrNull() }
    }.getOrNull()

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
    fun thumbnail(bitmap: Bitmap): Bitmap {
        val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }
    private fun encode(bitmap: Bitmap): String? {
        val small = thumbnail(bitmap)
        return try {
            val output = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 76, output)
            if (output.size() > 48000) { output.reset(); small.compress(Bitmap.CompressFormat.JPEG, 45, output) }
            if (output.size() > 48000) null else "base64:" + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally { if (small !== bitmap) small.recycle() }
    }
}
