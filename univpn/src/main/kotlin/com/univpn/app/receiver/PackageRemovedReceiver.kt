package com.univpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.univpn.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(context).appRouteDao().deleteByPackage(pkg)
            Log.i(TAG, "Removed route for uninstalled package: $pkg")
        }
    }

    companion object {
        private const val TAG = "UniVPN"
    }
}
