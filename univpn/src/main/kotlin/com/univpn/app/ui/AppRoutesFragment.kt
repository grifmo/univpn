package com.univpn.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.AppRoute
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

class AppRoutesFragment : Fragment(R.layout.fragment_app_routes),
                          BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mainFragAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }
    override fun getMainFragmentAdapter() = mainFragAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentProfiles: List<VpnProfile> = emptyList()
    private lateinit var adapter: AppAdapter
    private val showHiddenAppsFlow = MutableStateFlow(false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        adapter = AppAdapter(
            getProfiles = { currentProfiles },
            onAssign = { packageName, choice ->
                scope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.get(ctx).appRouteDao()
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
                    val dao = AppDatabase.get(ctx).appRouteDao()
                    val existing = dao.getByPackage(packageName)
                    if (existing != null) {
                        dao.upsert(existing.copy(isHidden = !existing.isHidden))
                    } else {
                        dao.upsert(AppRoute(packageName, profileId = null, isPassthrough = true, isHidden = true))
                    }
                }
            }
        )

        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@AppRoutesFragment.adapter
        }

        val toggleBtn = view.findViewById<Button>(R.id.toggleSystem)
        toggleBtn.setOnClickListener {
            val newValue = !showHiddenAppsFlow.value
            showHiddenAppsFlow.value = newValue
            toggleBtn.text = if (newValue) "Hide hidden apps" else "Show hidden apps"
        }

        loadData()
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }

    private fun loadData() {
        val ctx = requireContext()
        scope.launch {
            val installed = withContext(Dispatchers.IO) { queryInstalledApps() }
            val db = AppDatabase.get(ctx)

            combine(
                db.appRouteDao().getAll().flowOn(Dispatchers.IO),
                db.vpnProfileDao().getAll().flowOn(Dispatchers.IO),
                showHiddenAppsFlow
            ) { routes, profiles, showHidden -> Triple(routes, profiles, showHidden) }
                .collect { (routes, profiles, showHidden) ->
                    currentProfiles = profiles
                    val routeMap = routes.associateBy { it.packageName }
                    adapter.submitList(installed.mapNotNull { app ->
                        val route = routeMap[app.packageName]
                        val hidden = route?.isHidden == true
                        if (!showHidden && hidden) return@mapNotNull null
                        val explicitDbNoVpn = route != null && route.profileId == null && !route.isPassthrough
                        val implicitNoVpn = route == null && !app.isSystemApp
                        app.copy(
                            currentProfileId = route?.profileId,
                            isPassthrough = route?.isPassthrough ?: false,
                            isExplicitNoVpn = explicitDbNoVpn || implicitNoVpn,
                            isHidden = hidden
                        )
                    })
                }
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        val pm = requireContext().packageManager
        val leanback = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LEANBACK_LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()
        val launcher = pm.queryIntentActivities(
            Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER"), 0
        ).map { it.activityInfo.packageName }.toSet()

        return (leanback + launcher)
            .filter { it != requireContext().packageName }
            .mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                   (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    AppItem(
                        packageName = pkg,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(pkg),
                        currentProfileId = null,
                        isSystemApp = isSystem
                    )
                } catch (e: PackageManager.NameNotFoundException) { null }
            }
            .sortedBy { it.label.lowercase() }
    }
}
