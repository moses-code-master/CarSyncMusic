package app.carsync.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Finds existing LAN relay servers. Does not start a server on the Android device. */
class LanDiscovery(context: Context, private val found: (String) -> Unit) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) { stop() }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (info.port in 1..65535) found("http://$host:${info.port}")
                    }
                })
            }
        }
        listener = l
        try { manager.discoverServices("_carsync._tcp.", NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { listener = null }
    }
    fun stop() {
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
    }
}
