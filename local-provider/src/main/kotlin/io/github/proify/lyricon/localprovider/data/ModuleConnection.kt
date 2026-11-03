package io.github.proify.lyricon.localprovider.data

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object ModuleConnection {
    suspend fun call(context: Context, method: String, data: Bundle? = null): Bundle {
        var failure: Exception? = null
        repeat(5) { attempt ->
            try {
                context.contentResolver.call(ProviderStore.URI, method, null, data)?.let { return it }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error
            }
            if (attempt == 0) wake(context)
            delay(250L * (attempt + 1))
        }
        throw IllegalStateException("Local provider connection unavailable", failure)
    }

    fun wake(context: Context) {
        val token = PendingIntent.getBroadcast(context, 0,
            Intent("io.github.proify.lyricon.localprovider.PREFERENCE_SENDER").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.sendBroadcast(Intent().setComponent(ComponentName("io.github.proify.lyricon.localprovider",
            "io.github.proify.lyricon.localprovider.data.PreferenceWakeReceiver"))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND).putExtra("sender", token))
    }
}
