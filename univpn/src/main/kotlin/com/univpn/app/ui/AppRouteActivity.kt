package com.univpn.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.AppRoute
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import com.univpn.app.ui.RouteChoice

class AppRouteActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentProfiles: List<VpnProfile> = emptyList()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_route)

        adapter = AppAdapter(
            getProfiles = { currentProfiles },
            onAssign = { packageName, choice ->
                scope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.get(this@AppRouteActivity).appRouteDao()
                    when (choice) {
                        is RouteChoice.Default     -> dao.deleteByPackage(packageName)
                        is RouteChoice.NoVpn       -> dao.upsert(AppRoute(packageName, null, isPassthrough = false))
                        is RouteChoice.Passthrough -> dao.upsert(AppRoute(packageName, null, isPassthrough = true))
                        is RouteChoice.Profile     -> dao.upsert(AppRoute(packageName, choice.id, isPassthrough = false))
                    }
                }
            },
            onToggleHide = { packageName ->
                scope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.get(this@AppRouteActivity).appRouteDao()
                    val existing = dao.getByPackage(packageName)
                    if (existing != null) {
                        dao.upsert(existing.copy(isHidden = !existing.isHidden))
                    } else {
                        dao.upsert(AppRoute(packageName, profileId = null, isPassthrough = true, isHidden = true))
                    }
                }
            }
        )

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@AppRouteActivity)
            adapter = this@AppRouteActivity.adapter
        }

        loadData()
    }

    private fun loadData() {
        scope.launch {
            val installed = withContext(Dispatchers.IO) { queryInstalledApps() }
            val db = AppDatabase.get(this@AppRouteActivity)

            combine(
                db.appRouteDao().getAll().flowOn(Dispatchers.IO),
                db.vpnProfileDao().getAll().flowOn(Dispatchers.IO)
            ) { routes, profiles -> Pair(routes, profiles) }
                .collect { (routes, profiles) ->
                    currentProfiles = profiles
                    val routeMap = routes.associateBy { it.packageName }
                    adapter.submitList(installed.map { app ->
                        val route = routeMap[app.packageName]
                        app.copy(
                            currentProfileId = route?.profileId,
                            isPassthrough = route?.isPassthrough ?: false,
                            isExplicitNoVpn = route != null && route.profileId == null && !route.isPassthrough
                        )
                    })
                }
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        val pm = packageManager
        val leanback = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LEANBACK_LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()
        val launcher = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()

        return (leanback + launcher)
            .filter { it != packageName }
            .mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppItem(
                        packageName = pkg,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(pkg),
                        currentProfileId = null
                    )
                } catch (e: PackageManager.NameNotFoundException) { null }
            }
            .sortedBy { it.label.lowercase() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
