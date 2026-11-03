package io.github.proify.lyricon.localprovider

import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.data.ProviderSettings
import io.github.proify.lyricon.localprovider.data.ProviderStore
import kotlinx.serialization.encodeToString
import org.junit.Test

class PlayerFavoriteSetupTest {
    @Test fun prepareCatalog() {
        prepare()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("local_provider_ui", 0)
        val backup = context.getSharedPreferences("favorite_transport_test_backup", 0)
        if (!backup.getBoolean("catalog_created", false)) backup.edit().putBoolean("catalog_created", true).apply {
            listOf("snapshot", "events", "choices").forEach { putString("catalog_$it", prefs.getString(it, null)) }
        }.commit()
        val folder = java.io.File(context.filesDir, "cold-start-test-catalog").apply { mkdirs() }
        java.io.File(folder, "Cold recovery fixture.lrc").writeText(
            "[ti:Cold recovery fixture]\n[ar:Fixture artist]\n[00:00.00]Automatic directory recovery\n[00:03.00]Recovered after closing the module\n")
        ProviderStore(context).update { it.copy(folders = it.folders +
            io.github.proify.lyricon.localprovider.data.LyricFolder(folder.absolutePath, "Recovery test")) }
    }

    @Test fun verifyCatalogReportAndRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val snapshot = ProviderStore(context).snapshot()
            org.junit.Assert.assertEquals("Cold recovery fixture", snapshot.song?.name)
            org.junit.Assert.assertEquals("cold-recovery-report", snapshot.message)
        } finally { restore() }
    }

    @Test fun hostUnavailableProvider() {
        prepare()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = android.content.ComponentName(context, io.github.proify.lyricon.localprovider.data.StateProvider::class.java)
        val oldComponent = context.packageManager.getComponentEnabledSetting(component)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                if (intent.action == "lyricon.test.DISABLE_PROVIDER") context.packageManager.setComponentEnabledSetting(
                    component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                else done.set(true)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter().apply {
            addAction("lyricon.test.DISABLE_PROVIDER"); addAction("lyricon.test.FINISH_PROVIDER")
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try {
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("fixture", "READY") })
            val deadline = System.currentTimeMillis() + 60000
            while (!done.get()) { check(System.currentTimeMillis() < deadline); Thread.sleep(100) }
            Thread.sleep(1500)
        } finally {
            context.unregisterReceiver(receiver)
            context.packageManager.setComponentEnabledSetting(component, oldComponent, android.content.pm.PackageManager.DONT_KILL_APP)
            restore()
        }
    }

    @Test fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProviderStore(context)
        val backup = context.getSharedPreferences("favorite_transport_test_backup", 0)
        if (!backup.contains("settings")) backup.edit().putString("settings", ProviderStore.json.encodeToString(store.settings())).commit()
        store.update { it.copy(players = it.players + "io.github.proify.lyricon") }
    }

    @Test fun restore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backup = context.getSharedPreferences("favorite_transport_test_backup", 0)
        if (backup.getBoolean("catalog_created", false)) {
            context.getSharedPreferences("local_provider_ui", 0).edit().apply {
                listOf("snapshot", "events", "choices").forEach { key ->
                    val value = backup.getString("catalog_$key", null)
                    if (value == null) remove(key) else putString(key, value)
                }
            }.commit()
            java.io.File(context.filesDir, "cold-start-test-catalog/Cold recovery fixture.lrc").delete()
            java.io.File(context.filesDir, "cold-start-test-catalog").delete()
        }
        backup.getString("settings", null)?.let { raw ->
            val previous = ProviderStore.json.decodeFromString<ProviderSettings>(raw)
            ProviderStore(context).update { previous }
            backup.edit().clear().commit()
        }
    }
}
