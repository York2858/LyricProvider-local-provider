package io.github.proify.lyricon.localprovider.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** An explicit wake-up lets a trusted hooked player restart delivery after the UI is closed. */
class PreferenceWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("sender", PendingIntent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra("sender") as? PendingIntent)
        sender ?: return
        val enabled = ProviderStore(context).settings().players
        val packages = context.packageManager.getPackagesForUid(sender.creatorUid).orEmpty()
        if (packages.none { it in enabled }) return
        // Starting this process is sufficient; the player's durable outbox retries the provider call.
    }
}
