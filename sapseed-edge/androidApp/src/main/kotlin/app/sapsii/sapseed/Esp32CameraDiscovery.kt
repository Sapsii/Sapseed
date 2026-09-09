package app.sapsii.sapseed

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

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
            Log.i(LOG_TAG, "NSD found name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
            if (!isSapseedCamera(serviceInfo)) return
            @Suppress("DEPRECATION")
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(LOG_TAG, "NSD resolve failed for ${serviceInfo.serviceName} ($errorCode)")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = resolved.host?.hostAddress ?: run {
                            Log.w(LOG_TAG, "NSD resolved ${resolved.serviceName} with no host")
                            return
                        }
                        val path = resolved.attributes?.get("stream")
                            ?.let { String(it, Charsets.UTF_8) }
                            ?: "/stream"
                        val url = "http://$host:${resolved.port}$path"
                        Log.i(LOG_TAG, "NSD resolved ${resolved.serviceName} -> $url")
                        onCameraFound(resolved.serviceName, url)
                    }
                },
            )
        }
    }

    fun start() {
        if (running) return
        running = true
        Log.i(LOG_TAG, "NSD browsing for $SERVICE_TYPE")
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun close() {
        if (!running) return
        runCatching { manager.stopServiceDiscovery(discoveryListener) }
        running = false
    }

    private fun isSapseedCamera(serviceInfo: NsdServiceInfo): Boolean {
        val advertised = serviceInfo.serviceType.trim().trimEnd('.')
        return advertised.equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)
    }

    private companion object {
        const val SERVICE_TYPE = "_sapseedcam._tcp."
        const val LOG_TAG = "SapseedNsd"
    }
}
