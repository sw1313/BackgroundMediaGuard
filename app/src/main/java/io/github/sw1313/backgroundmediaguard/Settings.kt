package io.github.sw1313.backgroundmediaguard

object Settings {
    const val GROUP = "guard"
    const val KEY_ENABLED = "enabled"
    const val KEY_AUDIO_HARDENING = "audio_hardening"
    const val KEY_FREEZER = "freezer"
    const val KEY_HYPEROS_ZERO_DATA = "hyperos_zero_data"
    const val KEY_CONTROL_KEEPALIVE = "control_keepalive"
    const val KEY_JS_BRIDGE = "emby_js_bridge"
    const val KEY_PLEX_PIP_KEEP_PLAYING = "plex_pip_keep_playing"
    const val KEY_PLEX_MEDIA_NOTIFICATION = "plex_media_notification"
    const val KEY_PLEX_SURFACE_RESTORE = "plex_surface_restore"
    const val KEY_PLEX_PREVENT_RESTART = "plex_prevent_restart"
    const val KEY_PLEX_CODEC_ERROR_GUARD = "plex_codec_error_guard"
    const val KEY_JELLYFIN_PLAYER_RESTORE = "jellyfin_player_restore"
    const val KEY_TARGETS = "targets"
    const val KEY_ALWAYS_PROTECT = "always_protect"
    const val KEY_GRACE_SECONDS = "grace_seconds"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_AUDIO_HARDENING = true
    const val DEFAULT_FREEZER = true
    const val DEFAULT_HYPEROS_ZERO_DATA = true
    const val DEFAULT_ALWAYS_PROTECT = false
    const val DEFAULT_GRACE_SECONDS = 120
    const val DEFAULT_PLEX_PIP_KEEP_PLAYING = false
    const val DEFAULT_PLEX_MEDIA_NOTIFICATION = false
    const val DEFAULT_PLEX_SURFACE_RESTORE = false
    const val DEFAULT_PLEX_PREVENT_RESTART = false
    const val DEFAULT_PLEX_CODEC_ERROR_GUARD = false
    const val DEFAULT_JELLYFIN_PLAYER_RESTORE = true

    const val PACKAGE_EMBY = "com.mb.android"
    const val PACKAGE_PLEX = "com.plexapp.android"
    const val PACKAGE_JELLYFIN = "org.jellyfin.mobile"

    fun appKey(key: String, packageName: String): String = "app.$packageName.$key"

    /** Official Emby is on by default; other packages (including repacks) opt in. */
    fun defaultJsBridge(packageName: String): Boolean = packageName == PACKAGE_EMBY

    fun isPlexPackage(packageName: String): Boolean = packageName == PACKAGE_PLEX

    fun isJellyfinPackage(packageName: String): Boolean = packageName == PACKAGE_JELLYFIN
}
