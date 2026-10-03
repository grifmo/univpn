package com.univpn.app.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

object AppCategoryClassifier {

    enum class AppCategory { VIDEO, AUDIO, OTHER }

    private val KNOWN_VIDEO = setOf(
        // Netflix
        "com.netflix.ninja", "com.netflix.mediaclient",
        // Amazon Prime Video (package varies by device/store)
        "com.amazon.avod.thirdpartyclient",
        "com.amazon.amazonvideo.livingroom",
        "com.amazon.amazonvideo.livingroom.nvidia",
        // Disney+
        "com.disney.disneyplus",
        // Apple TV+
        "com.apple.atve.androidtv.appletv",
        // HBO Max / Max
        "com.hbo.hbonow", "com.hbomax.us", "com.wbd.stream",
        // Hulu
        "com.hulu.plus",
        // Paramount+
        "com.paramount.plus",
        // Peacock
        "com.peacocktv.peacockandroid",
        // Discovery+
        "com.discovery.discoveryplus", "com.discovery.discoveryplus.androidtv",
        // ESPN+
        "com.espn.score_center",
        // Tubi
        "com.tubitv",
        // Pluto TV
        "tv.pluto.android",
        // Vudu
        "air.com.vudu.air.DownloadManager",
        // MUBI
        "com.mubi.app",
        // Crunchyroll
        "com.crunchyroll.crunchyroid",
        // Twitch
        "tv.twitch.android.app",
        // YouTube / YouTube TV
        "com.google.android.youtube", "com.google.android.youtube.tv",
        // Plex / Jellyfin / Kodi
        "com.plexapp.android", "org.jellyfin.androidtv", "org.xbmc.kodi",
        // UK broadcasters
        "com.bbc.iplayer.android",
        "com.channel4.androidapp",
        "com.itv.hub.android",         // ITV Hub (legacy)
        "air.ITVMobilePlayer",          // ITVX (rebranded ITV Hub)
        "com.sky.go",
        "com.nowtv.android", "com.nowtv.androidtv",
        "uk.co.channel5.my5",
        // DAZN
        "com.dazn",
        // fuboTV
        "tv.fubo.mobile",
        // German/European broadcasters
        "com.zdf.android.mediathek",   // ZDF Mediathek
        "de.zdf.mediathek.tivi",       // ZDFtivi
        "de.kika.player.androidtv",    // KiKa
        "tv.arte.plus7",               // ARTE
        // Swedish/Nordic
        "se.svt.android.svtplay",      // SVT Play
        // Live TV
        "com.zattoo.player",           // Zattoo
        "com.google.android.tv",       // Android TV Live Channels
    )

    private val KNOWN_AUDIO = setOf(
        "com.spotify.music", "com.spotify.tv.android",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
        "com.apple.android.music",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.qobuz.music",
        "com.soundcloud.android",
        "com.pandora.android",
        "com.clearchannel.iheartradio.controller",
        "uk.co.bbc.sounds",
        "au.com.shiftyjelly.pocketcasts",
        "com.overcast.podcast",
        "fm.castbox.audiobook.radio.podcast",
    )

    fun classify(pm: PackageManager, packageName: String): AppCategory {
        // Layer 1: curated list — most reliable for TV streaming apps that don't set appCategory
        if (packageName in KNOWN_VIDEO) return AppCategory.VIDEO
        if (packageName in KNOWN_AUDIO) return AppCategory.AUDIO

        // Layer 2: ApplicationInfo.category (set by well-behaved apps in their manifest; API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()?.also { info ->
            if (info.category == ApplicationInfo.CATEGORY_VIDEO) return AppCategory.VIDEO
            if (info.category == ApplicationInfo.CATEGORY_AUDIO) return AppCategory.AUDIO
        }

        // Layer 3: MediaBrowserService intent — reliable signal for music/podcast apps
        val handlers = pm.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService"), 0
        )
        if (handlers.any { it.serviceInfo.packageName == packageName }) return AppCategory.AUDIO

        return AppCategory.OTHER
    }
}
