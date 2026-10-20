package app.carsync.music

/** All positions are voluntary; missing, offline or stale cars remain visible in the roster. */
data class ConvoyMapCar(
    val car: Car,
    val usableLocation: ConvoyPosition?,
    val distanceFromReferenceMeters: Double?,
    val locationStatus: String
)
data class ConvoyMapSnapshot(
    val referenceId: String?,
    val referenceName: String?,
    val cars: List<ConvoyMapCar>
) {
    val onlineCount get() = cars.count { it.car.online }
    val locatedCount get() = cars.count { it.usableLocation != null }
}

/** Distances are GPS-derived straight lines, never driving times or route distances. */
object ConvoyMapData {
    fun build(state: AppState, preferredReferenceId: String?, nowMs: Long): ConvoyMapSnapshot {
        if (!state.connected) return ConvoyMapSnapshot(null, null, emptyList())
        val eligible = state.cars.filter { it.online && it.location?.usable(nowMs) == true }
        val reference = eligible.firstOrNull { it.memberId == preferredReferenceId }
            ?: eligible.firstOrNull { it.memberId == state.memberId }
            ?: eligible.firstOrNull { it.role == "host" }
            ?: eligible.firstOrNull()
        val entries = state.cars.map { car ->
            val location = car.location?.takeIf { car.online && it.usable(nowMs) }
            val distance = if (location != null && reference?.location != null) {
                ConvoyGeometry.separationMeters(reference.location, location, nowMs)
            } else null
            val status = when {
                !car.online -> "Offline"
                car.location == null -> "Location not shared or unavailable"
                !car.location.usable(nowMs) -> "GPS stale or inaccurate"
                else -> "Live GPS"
            }
            ConvoyMapCar(car, location, distance, status)
        }
        return ConvoyMapSnapshot(reference?.memberId, reference?.name, entries)
    }
}
