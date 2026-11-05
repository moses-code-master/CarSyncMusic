package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class LocalDiagnosticsTest {
    @Test fun reportDropsMessagesCausesAndFilePaths() {
        val error = IllegalStateException("Bearer PRIVATE_TOKEN at /private/song.mp3", RuntimeException("GPS 1.23,4.56"))
        error.stackTrace = arrayOf(StackTraceElement("app.carsync.music.Player", "play", "/private/OWNER/file.kt", 42))
        val report = LocalDiagnostics.sanitize(error)
        assertTrue(report.contains("IllegalStateException")); assertTrue(report.contains("Player.play:42"))
        listOf("PRIVATE_TOKEN", "song.mp3", "GPS", "OWNER", "/private").forEach { assertFalse(report.contains(it)) }
    }
    @Test fun reportIsBounded() {
        val error = RuntimeException()
        error.stackTrace = Array(500) { StackTraceElement("Class", "method", "file", it) }
        assertEquals(42, LocalDiagnostics.sanitize(error).lines().filter { it.isNotBlank() }.size)
        assertTrue(LocalDiagnostics.sanitize(error).length <= 16000)
    }
}
