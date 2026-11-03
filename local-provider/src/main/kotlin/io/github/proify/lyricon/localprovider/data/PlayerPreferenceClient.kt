package io.github.proify.lyricon.localprovider.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object PlayerPreferenceClient {
    suspend fun collect(context: Context) = coroutineScope {
        if (!PreferenceRecordStore(context).enabled()) return@coroutineScope
        ProviderStore(context).settings().players.map { player -> async {
            try { collectPlayer(context, player) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.w("LocalProvider", "Preference collection failed for $player: ${error.javaClass.simpleName}")
            }
        } }.awaitAll()
    }

    suspend fun collectPlayer(context: Context, player: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        repeat(20) {
            val batch = request(context, player, Bundle().apply { putString("operation", "read") }) ?: return@withContext count
            check(batch.getBoolean("accepted")) { batch.getString("error") ?: "播放器记录读取失败" }
            val journal = batch.getString("journal_id").orEmpty()
            @Suppress("DEPRECATION")
            val events = batch.getParcelableArrayList<Bundle>("events").orEmpty().map {
                val raw = it.getString("json").orEmpty()
                require(raw.length <= 40_000)
                it.getLong("sequence") to ProviderStore.json.decodeFromString<PreferenceObservation>(raw)
            }
            if (events.isEmpty()) return@withContext count
            PreferenceRecordStore(context).importPlayerBatch(player, journal, events)
            val ack = request(context, player, Bundle().apply {
                putString("operation", "ack"); putString("journal_id", journal); putLong("through", events.last().first)
            }) ?: return@withContext count
            check(ack.getBoolean("accepted")) { ack.getString("error") ?: "播放器记录确认失败" }
            count += events.size
            if (!batch.getBoolean("more")) return@withContext count
        }
        count
    }

    private suspend fun request(context: Context, player: String, data: Bundle): Bundle? = withTimeoutOrNull(1500) {
        suspendCancellableCoroutine { continuation ->
            val id = java.util.UUID.randomUUID().toString()
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                val trusted = context.packageManager.getPackagesForUid(message.sendingUid)?.contains(player) == true
                if (trusted && message.data.getString("request_id") == id && continuation.isActive) continuation.resume(message.data)
                true
            })
            val sender = PendingIntent.getBroadcast(context, 0, Intent("io.github.proify.lyricon.localprovider.SYNC_SENDER")
                .setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                context.sendBroadcast(Intent("io.github.proify.lyricon.localprovider.PLAYER_PREFERENCES").setPackage(player)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(data).putExtra("sender", sender)
                    .putExtra("reply", reply).putExtra("request_id", id))
            } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
        }
    }
}
