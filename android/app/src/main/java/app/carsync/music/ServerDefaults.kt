package app.carsync.music

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Stable builds use the production convoy backend by default.
 * Explicitly configured endpoints remain untouched, as do endpoints of active sessions.
 */
object ServerDefaults {
    const val LEGACY_URL = "https://carsync-music.onrender.com"
    const val CONVOY_URL = "https://carsync-music-convoy-test.onrender.com"
    const val RADIO_URL = "https://carsync-music-radio-test.onrender.com"
    const val MAP_URL = "https://carsync-music-map-test.onrender.com"

    /**
     * Users may select a trusted HTTPS server or an explicitly local HTTP relay.
     * Reject credentials, paths and fragments to prevent accidental token leaks
     * and confusing cross-server invitations.
     */
    fun configuredRoot(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        val url = clean.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a full http:// or https:// server address")
        require(url.encodedPath == "/" && url.query == null && url.fragment == null &&
            url.username.isEmpty() && url.password.isEmpty()) {
            "Use the server root address without paths, credentials, queries or fragments"
        }
        require(url.isHttps || isLanHost(url.host)) {
            "Public servers require HTTPS. HTTP is allowed only on a private network."
        }
        return url.toString().trimEnd('/')
    }

    fun resolve(saved: String?, manuallyConfigured: Boolean, hasActiveSession: Boolean): String {
        val value = saved?.trim()?.trimEnd('/').orEmpty()
        if (hasActiveSession) return value.ifBlank { LEGACY_URL }
        if (manuallyConfigured && value.isNotBlank()) return value
        return if (value.isBlank() || value in setOf(LEGACY_URL, CONVOY_URL, RADIO_URL, MAP_URL)) LEGACY_URL else value
    }
}
