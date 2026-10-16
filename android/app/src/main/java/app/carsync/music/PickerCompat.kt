package app.carsync.music

// OEM head units may omit DocumentsUI or reject a file-provider launch.
internal fun launchPickerSafely(primary: () -> Unit, fallback: (() -> Unit)? = null, unavailable: () -> Unit) {
    try { primary(); return } catch (_: RuntimeException) { }
    if (fallback != null) try { fallback(); return } catch (_: RuntimeException) { }
    unavailable()
}
