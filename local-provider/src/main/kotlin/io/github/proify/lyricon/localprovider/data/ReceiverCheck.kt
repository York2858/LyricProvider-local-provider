package io.github.proify.lyricon.localprovider.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.IActivePlayerListener
import io.github.proify.lyricon.subscriber.IRemoteService
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ReceiverState(
    val connected: Boolean = false, val provider: String = "", val player: String = "",
    val song: Song? = null, val romanizationVisible: Boolean? = null,
    val translationVisible: Boolean? = null, val message: String = "等待词幕连接"
)

/** A read-only subscription to the central service; it never publishes or calls AI. */
class ReceiverCheck(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var remote: IRemoteService? = null
    private var started = false
    var state = ReceiverState()
        private set
    fun start() {
        if (started) return
        started = true
        val token = ++generation
        state = ReceiverState()
        fun update(block: (ReceiverState) -> ReceiverState) {
            main.post { if (generation == token && started) { state = block(state); changed() } }
        }
        val listener = object : IActivePlayerListener.Stub() {
            override fun onActiveProviderChanged(providerInfo: ByteArray?) {
                val info = providerInfo?.let { runCatching { ProviderStore.json.parseToJsonElement(it.decodeToString()).jsonObject }.getOrNull() }
                update { it.copy(provider = info?.get("providerPackageName")?.jsonPrimitive?.content.orEmpty(),
                    player = info?.get("playerPackageName")?.jsonPrimitive?.content.orEmpty(), song = null) }
            }
            override fun onSongChanged(song: ByteArray?) {
                val decoded = song?.let { runCatching { ProviderStore.json.decodeFromString<Song>(it.decodeToString()) }.getOrNull() }
                update { it.copy(song = decoded, message = if (decoded == null) "未收到可解析的整首歌词" else "已收到词幕当前歌词") }
            }
            override fun onPlaybackStateChanged(isPlaying: Boolean) {}
            override fun onSeekTo(position: Long) {}
            override fun onReceiveText(text: String?) { update { it.copy(song = null, message = "当前来源只发送单句文本") } }
            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) { update { it.copy(translationVisible = isDisplayTranslation) } }
            override fun onDisplayRomaChanged(isDisplayRoma: Boolean) { update { it.copy(romanizationVisible = isDisplayRoma) } }
        }
        val binder = object : ISubscriberBinder.Stub() {
            override fun getSubscriberInfo(): ByteArray = buildJsonObject {
                put("packageName", context.packageName); put("processName", context.packageName + ":receive-check")
            }.toString().toByteArray()
            override fun onRegistrationCallback(service: IRemoteService?) {
                if (generation != token || !started) { runCatching { service?.disconnect() }; return }
                remote = service
                runCatching { service?.setActivePlayerListener(listener) }
                    .onSuccess { update { it.copy(connected = service != null) } }
                    .onFailure { update { it.copy(message = "词幕连接失败，请确认模块已激活") } }
            }
        }
        context.sendBroadcast(Intent("io.github.proify.lyricon.lyric.bridge.REGISTER_SUBSCRIBER")
            .setPackage("com.android.systemui").putExtra("bundle", Bundle().apply { putBinder("binder", binder) }))
        main.postDelayed({ if (generation == token && !state.connected) update { it.copy(message = "未连接词幕，请确认词幕已激活") } }, 8000)
    }
    fun stop() {
        started = false; generation++; main.removeCallbacksAndMessages(null)
        val service = remote; remote = null
        runCatching { service?.disconnect() }
    }
}
