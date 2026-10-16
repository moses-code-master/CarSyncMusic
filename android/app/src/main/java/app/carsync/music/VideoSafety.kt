package app.carsync.music

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Proxy

object VideoSafetyPolicy {
    fun canShowPicture(parked: Boolean, driving: Boolean, audioOnly: Boolean, automotive: Boolean, vehicleKnown: Boolean, restricted: Boolean) =
        parked && !driving && !audioOnly && !restricted && (!automotive || vehicleKnown)
}

// android.car is optional on generic head units. No speed inference or manufacturer bypass.
internal class VehicleSafetyMonitor(private val context: Context, private val onState: (Boolean, Boolean) -> Unit) {
    val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val handler = Handler(Looper.getMainLooper())
    private var car: Any? = null
    private var manager: Any? = null
    private var listener: Any? = null
    fun start() {
        if (!automotive) return
        Thread({
            try {
                val carClass = Class.forName("android.car.Car")
                car = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
                manager = carClass.getMethod("getCarManager", String::class.java).invoke(car, "uxrestriction")
                val type = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
                fun emit(restrictions: Any) {
                    val blocked = restrictions.javaClass.getMethod("isRequiresDistractionOptimization").invoke(restrictions) as Boolean
                    handler.post { onState(true, blocked) }
                }
                listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                    when (method.name) {
                        "onUxRestrictionsChanged" -> { args?.firstOrNull()?.let { emit(it) }; null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "CarSync vehicle safety listener"
                        else -> null
                    }
                }
                manager!!.javaClass.getMethod("registerListener", type).invoke(manager, listener)
                manager!!.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager)?.let { emit(it) }
            } catch (_: Exception) { handler.post { onState(false, true) } } // Fail closed on Automotive OS.
        }, "CarSync-vehicle-safety").apply { isDaemon = true; start() }
    }
    fun close() {
        runCatching { manager?.javaClass?.getMethod("unregisterListener")?.invoke(manager) }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
    }
}
