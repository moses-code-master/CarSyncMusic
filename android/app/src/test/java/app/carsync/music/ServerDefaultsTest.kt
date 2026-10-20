package app.carsync.music

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDefaultsTest {
    @Test fun brandNewInstallationUsesMatchingBetaBackend() {
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve(null, false, false))
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve("", false, false))
    }

    @Test fun existingLegacyDefaultMigratesWithoutSetup() {
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, false))
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL + "/", false, false))
    }

    @Test fun explicitOverridesAreNeverReplaced() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, true, false))
        assertEquals("https://example.net", ServerDefaults.resolve("https://example.net", true, false))
        assertEquals("http://192.168.10.5:8080", ServerDefaults.resolve("http://192.168.10.5:8080", false, false))
    }

    @Test fun activeSessionKeepsItsOriginalBackendUntilLeave() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, true))
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, false))
    }
}
