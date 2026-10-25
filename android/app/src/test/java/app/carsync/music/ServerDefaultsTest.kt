package app.carsync.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDefaultsTest {
    @Test fun brandNewInstallationUsesProductionBackend() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(null, false, false))
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve("", false, false))
    }

    @Test fun existingProductionDefaultRemainsStable() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, false))
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL + "/", false, false))
    }

    @Test fun explicitOverridesAreNeverReplaced() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, true, false))
        assertEquals(ServerDefaults.CONVOY_URL, ServerDefaults.resolve(ServerDefaults.CONVOY_URL, true, false))
        assertEquals(ServerDefaults.RADIO_URL, ServerDefaults.resolve(ServerDefaults.RADIO_URL, true, false))
        assertEquals("https://example.net", ServerDefaults.resolve("https://example.net", true, false))
        assertEquals("http://192.168.10.5:8080", ServerDefaults.resolve("http://192.168.10.5:8080", false, false))
    }

    @Test fun inactiveUncustomizedOldBetaMovesToProduction() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.CONVOY_URL, false, false))
    }

    @Test fun uncustomizedRadioBetaMigratesToProductionAfterLeaving() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.RADIO_URL, false, false))
        assertEquals(ServerDefaults.RADIO_URL, ServerDefaults.resolve(ServerDefaults.RADIO_URL, false, true))
    }

    @Test fun activeSessionKeepsItsOriginalBackendUntilLeave() {
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, true))
        assertEquals(ServerDefaults.LEGACY_URL, ServerDefaults.resolve(ServerDefaults.LEGACY_URL, false, false))
    }

    @Test fun inactiveMapBetaMigratesToProductionButActiveOrManualMapStays() {
        assertEquals(ServerDefaults.LEGACY_URL,
            ServerDefaults.resolve(ServerDefaults.MAP_URL, false, false))
        assertEquals(ServerDefaults.MAP_URL,
            ServerDefaults.resolve(ServerDefaults.MAP_URL, false, true))
        assertEquals(ServerDefaults.MAP_URL,
            ServerDefaults.resolve(ServerDefaults.MAP_URL, true, false))
    }

    @Test fun configuredServerRejectsPublicCleartextAndEmbeddedCredentials() {
        val unsafe = listOf(
            "http://example.net", "http://8.8.8.8:8080",
            "https://user:password@example.net",
            "https://example.net/extra", "https://example.net/?token=secret",
            "https://example.net/#section", "ftp://example.net", ""
        )
        unsafe.forEach { endpoint ->
            assertTrue("Unsafe endpoint accepted: $endpoint",
                runCatching { ServerDefaults.configuredRoot(endpoint) }.isFailure)
        }
    }

    @Test fun configuredServerAcceptsHttpsAndPrivateLocalRelaysOnly() {
        assertEquals("https://example.net", ServerDefaults.configuredRoot(" https://example.net/ "))
        assertEquals("https://carsync-music-map-test.onrender.com",
            ServerDefaults.configuredRoot(ServerDefaults.MAP_URL))
        assertEquals("http://192.168.10.5:8080",
            ServerDefaults.configuredRoot("http://192.168.10.5:8080"))
        assertEquals("http://localhost:8080",
            ServerDefaults.configuredRoot("http://localhost:8080"))
        assertEquals("http://10.0.2.2:8080",
            ServerDefaults.configuredRoot("http://10.0.2.2:8080"))
    }

    @Test fun differentBetaServersRemainDistinctSessionEndpoints() {
        val radio = ServerDefaults.configuredRoot(ServerDefaults.RADIO_URL)
        val map = ServerDefaults.configuredRoot(ServerDefaults.MAP_URL)
        assertTrue("The radio and map beta URLs must not be mistaken as shared sessions",
            radio != map)
        assertEquals(radio, ServerDefaults.resolve(radio, true, false))
        assertEquals(map, ServerDefaults.resolve(map, true, false))
        assertEquals(radio, ServerDefaults.resolve(radio, false, true))
    }

}
