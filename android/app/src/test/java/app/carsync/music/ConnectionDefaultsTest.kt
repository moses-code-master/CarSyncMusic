package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

/** Bluetooth is an audio accessory, never a prerequisite for internet convoy sessions. */
class ConnectionDefaultsTest {
    @Test fun bluetoothCallMicrophoneIsOffOnNewInstall() {
        assertFalse(AppState().preferBluetoothRadio)
    }

    @Test fun convoyUsesAnInternetServerWithoutBluetoothConfiguration() {
        val server = ServerDefaults.resolve(null, manuallyConfigured = false, hasActiveSession = false)
        assertTrue(server.startsWith("https://"))
        assertEquals(ServerDefaults.RADIO_URL, server)
        assertFalse(AppState(endpoint = server).preferBluetoothRadio)
    }

    @Test fun manualInternetOrLocalRelayServerDoesNotEnableBluetooth() {
        assertEquals("https://my-convoy.example", ServerDefaults.resolve(
            "https://my-convoy.example", manuallyConfigured = true, hasActiveSession = false))
        assertFalse(AppState(endpoint = "http://192.168.1.8:8080").preferBluetoothRadio)
    }
}
