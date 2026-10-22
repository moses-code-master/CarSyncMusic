package app.carsync.music

/**
 * Beta APK defaults to its matching convoy backend.
 * Explicitly configured endpoints remain untouched, as do endpoints of active sessions.
 */
object ServerDefaults {
    const val LEGACY_URL = "https://carsync-music.onrender.com"
    const val CONVOY_URL = "https://carsync-music-convoy-test.onrender.com"
    const val RADIO_URL = "https://carsync-music-radio-test.onrender.com"
    const val MAP_URL = "https://carsync-music-map-test.onrender.com"

    fun resolve(saved: String?, manuallyConfigured: Boolean, hasActiveSession: Boolean): String {
        val value = saved?.trim()?.trimEnd('/').orEmpty()
        if (hasActiveSession) return value.ifBlank { LEGACY_URL }
        if (manuallyConfigured && value.isNotBlank()) return value
        return if (value.isBlank() || value == LEGACY_URL || value == CONVOY_URL || value == RADIO_URL) MAP_URL else value
    }
}
