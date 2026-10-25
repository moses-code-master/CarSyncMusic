package app.carsync.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build

/** Finds existing LAN relay servers. Does not start a server on the Android device. */
class LanDiscovery(context: Context, private val found: (String) -> Unit, private val diagnostic: (String) -> Unit = {}) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    fun start() {
        if (listener != null) return
        diagnostic("Searching for a local CarSync relay on Wi-Fi…")
        if (Build.VERSION.SDK_INT < 33) {
            multicastLock = wifi?.createMulticastLock("carsync-discovery")?.apply { setReferenceCounted(false); acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) { diagnostic("Local relay discovery failed (Android error $error). Use the internet server."); stop() }
            override fun onStopDiscoveryFailed(type: String, error: Int) { diagnostic("Local relay scan stopped with error $error.") }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { diagnostic("Found a local relay but could not resolve its address (error $error).") }
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (info.port in 1..65535) found("http://${if (host.contains(":")) "[${host.substringBefore("%")}]" else host}:${info.port}")
                    }
                })
            }
        }
        listener = l
        try { manager.discoverServices("_carsync._tcp.", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { diagnostic("Local relay scan could not start: ${e.message.orEmpty()}"); listener = null; multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null }
    }
    fun stop() {
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
