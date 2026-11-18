package io.github.proify.lyricon.localprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Optional SystemUI scope. Bridge still reads and displays the player's original lyricInfo. */
internal object NeutronSystemUiCompat : YukiBaseHooker() {
    private const val SELECTOR = "com.oplus.systemui.media.controls.pipeline.MediaActionPrioritySelectorImpl"
    private val loggedSupport = AtomicBoolean()

    override fun onHook() {
        if (processName != Constants.SYSTEM_UI_PACKAGE) return
        val loader = appClassLoader ?: return
        val selector = runCatching { Class.forName(SELECTOR, false, loader) }.getOrNull()
        if (selector == null) {
            log("OPlus lyric selector unavailable")
            return
        }
        val entrance = install(selector, "getLyricEntrance", Int::class.javaPrimitiveType!!, String::class.java) { call ->
            val result = call.result as? Int ?: return@install
            val resolved = NeutronLockscreenPolicy.entrance(result, call.args[0], ::activeNeutronLyrics)
            if (result != resolved) call.result = resolved
        }
        val support = install(selector, "resolveLyricSupport", Boolean::class.javaPrimitiveType!!,
            String::class.java, MediaMetadata::class.java) { call ->
            val result = call.result as? Boolean ?: return@install
            val resolved = NeutronLockscreenPolicy.support(result, call.args[0]) {
                (call.args[1] as? MediaMetadata)?.getString("lyricInfo")
            }
            if (result != resolved) {
                call.result = resolved
                if (loggedSupport.compareAndSet(false, true)) {
                    log("Enabled lyric display for Neutron with Local Provider metadata")
                }
            }
        }
        log("Neutron lockscreen hooks installed: entrance=$entrance support=$support")
    }

    private fun install(selector: Class<*>, name: String, resultType: Class<*>, vararg params: Class<*>,
                        handle: (XC_MethodHook.MethodHookParam) -> Unit): Boolean = runCatching {
        val method = selector.getDeclaredMethod(name, *params)
        if (method.returnType != resultType || Modifier.isStatic(method.modifiers)) return false
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!param.hasThrowable()) runCatching { handle(param) }
            }
        })
        true
    }.getOrDefault(false)

    private fun activeNeutronLyrics(): String? = appContext?.getSystemService(MediaSessionManager::class.java)
        ?.getActiveSessions(null)?.firstOrNull { it.packageName == Constants.NEUTRON_PLAYER_PACKAGE }
        ?.metadata?.getString("lyricInfo")

    private fun log(message: String) = XposedBridge.log("LocalProvider.NeutronUI: $message")
}
