package com.univpn.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.univpn.app.data.AppCategoryClassifier.AppCategory
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.AppRoute

object AppRouteSeeder {

    private const val PREF_KEY = "routes_seeded_v1"

    private val SYSTEM_SEEDS = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.nvidia.shield.ask",
        "com.nvidia.nvgamecast",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    suspend fun seedIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("univpn_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY, false)) return

        val dao = AppDatabase.get(context).appRouteDao()
        for (pkg in SYSTEM_SEEDS) {
            dao.upsert(AppRoute(pkg, profileId = null, isPassthrough = true, isHidden = true))
        }
        prefs.edit().putBoolean(PREF_KEY, true).apply()
    }

    /**
     * Inserts a default route for any user app that has no route yet. Safe to call on every
     * launch — uses insertIfAbsent so existing user customisations are never overwritten.
     *
     * VIDEO / AUDIO → NoVPN  (user should configure these individually)
     * OTHER         → Passthrough (launchers, settings, helpers keep the current tunnel)
     */
    suspend fun seedUserAppDefaults(context: Context) {
        val pm = context.packageManager
        val dao = AppDatabase.get(context).appRouteDao()

        val leanback = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LEANBACK_LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()
        val launcher = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()

        for (pkg in leanback + launcher) {
            if (pkg == context.packageName) continue
            if (pkg in SYSTEM_SEEDS) continue
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                           (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) continue

            val route = when (AppCategoryClassifier.classify(pm, pkg)) {
                AppCategory.VIDEO, AppCategory.AUDIO ->
                    AppRoute(pkg, profileId = null, isPassthrough = false)
                AppCategory.OTHER ->
                    AppRoute(pkg, profileId = null, isPassthrough = true)
            }
            dao.insertIfAbsent(route)
        }
    }
}
