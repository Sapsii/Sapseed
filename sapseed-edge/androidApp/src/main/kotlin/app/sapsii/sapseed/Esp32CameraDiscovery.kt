package app.sapsii.sapseed

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class Esp32CameraDiscovery(
    context: Context,
    private val onCameraFound: (name: String, streamUrl: String) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var running = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) { running = false }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
            onError("Camera discovery failed ($errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith(SERVICE_TYPE)) return
            @Suppress("DEPRECATION")
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = resolved.host?.hostAddress ?: return
                        val url = "http://$host:${resolved.port}/stream"
                        onCameraFound(resolved.serviceName, url)
                    }
                },
            )
        }
    }

    fun start() {
        if (running) return
        running = true
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun close() {
        if (!running) return
        runCatching { manager.stopServiceDiscovery(discoveryListener) }
        running = false
    }

    private companion object {
        const val SERVICE_TYPE = "_sapseedcam._tcp."
    }
}
