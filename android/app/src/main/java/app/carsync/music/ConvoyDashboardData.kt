package app.carsync.music

/** Metrics displayed on the convoy home screen. All distances are straight-line GPS estimates. */
data class ConvoyDashboardStats(
    val onlineCars: Int,
    val registeredCars: Int,
    val farthestMeters: Double?,
    val farthestName: String?,
    val availableLocations: Int
) {
    val farthestLabel: String get() = farthestMeters?.let {
        ConvoyGeometry.label(it).removeSuffix(" away")
    } ?: "—"
}

object ConvoyDashboardData {
    fun from(state: AppState, nowMs: Long = System.currentTimeMillis()): ConvoyDashboardStats {
        if (!state.connected) return ConvoyDashboardStats(0, 0, null, null, 0)
        val online = state.cars.filter { it.online }
        val own = online.firstOrNull { it.memberId == state.memberId }?.location
        val farthest = online.asSequence()
            .filter { it.memberId != state.memberId }
            .mapNotNull { car ->
                ConvoyGeometry.separationMeters(own, car.location, nowMs)?.let { car to it }
            }.maxByOrNull { it.second }
        return ConvoyDashboardStats(
            onlineCars = online.size,
            registeredCars = state.cars.size,
            farthestMeters = farthest?.second,
            farthestName = farthest?.first?.name,
            availableLocations = online.count { it.location?.usable(nowMs) == true }
        )
    }
}
