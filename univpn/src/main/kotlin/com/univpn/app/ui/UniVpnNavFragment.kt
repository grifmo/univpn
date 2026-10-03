package com.univpn.app.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import com.univpn.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UniVpnNavFragment : BrowseSupportFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initialSelectionDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        title = ""

        mainFragmentRegistry.registerFragment(
            PageRow::class.java,
            object : FragmentFactory<Fragment>() {
                override fun createFragment(row: Any): Fragment {
                    return when ((row as PageRow).id) {
                        ROW_APP_ROUTES -> AppRoutesFragment()
                        ROW_PROFILES -> ProfilesFragment()
                        ROW_SETTINGS -> SettingsFragment()
                        else -> AppRoutesFragment()
                    }
                }
            }
        )

        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        rowsAdapter.add(PageRow(HeaderItem(ROW_APP_ROUTES, "App Routes")))
        rowsAdapter.add(PageRow(HeaderItem(ROW_PROFILES, "Profiles")))
        rowsAdapter.add(PageRow(HeaderItem(ROW_SETTINGS, "Settings")))
        adapter = rowsAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Hide the Leanback title view — the nav items are self-labelling
        titleView?.visibility = View.GONE
        if (savedInstanceState == null && !initialSelectionDone) {
            scope.launch {
                val hasProfiles = withContext(Dispatchers.IO) {
                    AppDatabase.get(requireContext()).vpnProfileDao().getAll().first().isNotEmpty()
                }
                initialSelectionDone = true
                setSelectedPosition(if (hasProfiles) ROW_APP_ROUTES.toInt() else ROW_PROFILES.toInt(), false)
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }

    companion object {
        const val ROW_APP_ROUTES = 0L
        const val ROW_PROFILES = 1L
        const val ROW_SETTINGS = 2L
    }
}
