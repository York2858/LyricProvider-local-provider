package io.github.proify.lyricon.localprovider.xposed

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.localprovider.data.PreferenceObservation
import io.github.proify.lyricon.localprovider.data.RuntimeBridge
import io.github.proify.lyricon.localprovider.data.trackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object PlayerFavoriteReceiver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registered = false

    fun initialize(context: Context) {
        if (registered) return
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val sender = intent.getParcelableExtra<PendingIntent>("sender") ?: return
                val sync = intent.action == "io.github.proify.lyricon.localprovider.PLAYER_PREFERENCES"
                if (sync) {
                    if (sender.creatorPackage != "io.github.proify.lyricon.localprovider") return
                } else if (sender.creatorPackage !in setOf("io.github.proify.lyricon", "com.android.systemui")) return
                val reply = intent.getParcelableExtra<Messenger>("reply") ?: return
                if (sync) {
                    val pending = goAsync()
                    scope.launch {
                        val result = Bundle().apply { putString("request_id", intent.getStringExtra("request_id")) }
                        try {
                            val journal = PlayerFavoriteJournal(context)
                            when (intent.getStringExtra("operation")) {
                                "read" -> result.putAll(journal.batch())
                                "ack" -> journal.acknowledge(intent.getStringExtra("journal_id").orEmpty(), intent.getLongExtra("through", 0))
                                else -> error("未知记录请求")
                            }
                            result.putBoolean("accepted", true)
                        } catch (error: Exception) {
                            result.putBoolean("accepted", false); result.putString("error", error.message)
                        }
                        try { reply.send(Message.obtain(null, 1).apply { data = result }) } catch (_: Exception) { }
                        finally { pending.finish() }
                    }
                    return
                }
                val data = intent.getBundleExtra("track") ?: return
                val current = RuntimeBridge.currentTrackMetadata()
                val title = data.getString("title").orEmpty()
                val artist = data.getString("artist").orEmpty()
                if (title.isBlank() || title.length > 2000 || artist.length > 2000 ||
                    trackKey(title, artist) != trackKey(current.title, current.artist)) return
                val value = PreferenceObservation(context.packageName, data.getString("media_id").orEmpty(), title, artist,
                    data.getString("album").orEmpty(), data.getLong("duration"))
                val pending = goAsync()
                scope.launch {
                    val result = Bundle().apply { putString("request_id", intent.getStringExtra("request_id")) }
                    try {
                        val journal = PlayerFavoriteJournal(context)
                        if (data.containsKey("favorite")) {
                            check(RuntimeBridge.settings.recordPreferences && context.packageName in RuntimeBridge.settings.players) {
                                "请在本地歌词服务中启用当前播放器的收藏与评分记录"
                            }
                            journal.write(value, data.getBoolean("favorite"), data.getBoolean("seed"))
                        }
                        val favorite = journal.read(value)
                        result.putBoolean("accepted", true)
                        result.putBoolean("known", favorite != null)
                        result.putBoolean("favorite", favorite == true)
                        journal.rating(value)?.let { result.putInt("rating", it) }
                        reply.send(Message.obtain(null, 1).apply { this.data = result })
                    } catch (error: Exception) {
                        result.putBoolean("accepted", false)
                        result.putString("error", error.message ?: "播放器喜爱记录保存失败")
                        runCatching { reply.send(Message.obtain(null, 1).apply { this.data = result }) }
                    } finally { pending.finish() }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction("io.github.proify.lyricon.localprovider.PLAYER_FAVORITE")
            addAction("io.github.proify.lyricon.localprovider.PLAYER_PREFERENCES")
        }, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }
}
