package dev.denza.apps.feature.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ProxyConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONNECTED) return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val binder = intent.extras?.getBinder(EXTRA_BINDER) ?: return
        NavigationProxyClient.acceptConnection(token, binder)
    }

    companion object {
        const val ACTION_CONNECTED = "dev.denza.apps.navigation.PROXY_CONNECTED"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_BINDER = "binder"
    }
}
