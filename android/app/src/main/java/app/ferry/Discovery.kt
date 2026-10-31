package app.ferry

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.update

/** Advertises this phone over Bonjour and keeps [Ferry.macs] in sync with the Macs on the network. */
class Discovery(context: Context, private val port: Int) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val idsByServiceName = mutableMapOf<String, String>()
    private val attempts = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "Advertising failed: $error") }
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, error: Int) { Log.w(TAG, "Discovery failed: $error") }
        override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
        override fun onServiceLost(info: NsdServiceInfo) {
            val id = synchronized(this@Discovery) { idsByServiceName.remove(info.serviceName) } ?: return
            Ferry.macs.update { it - id }
        }
    }

    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = Ferry.deviceName
            serviceType = Wire.SERVICE_TYPE
            port = this@Discovery.port
            setAttribute("id", Store.deviceId)
            setAttribute("name", Ferry.deviceName)
            setAttribute("kind", "android")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { nsd.unregisterService(registration) }
        runCatching { nsd.stopServiceDiscovery(discovery) }
        Ferry.macs.value = emptyMap()
    }

    // NsdManager.resolveService handles one request at a time, so resolve found services in turn.
    private fun enqueue(info: NsdServiceInfo) {
        synchronized(this) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(this) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                retryLater(next)
                resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (!onResolved(info)) retryLater(next)
                resolveNext()
            }
        })
    }

    // A service that just restarted is often announced before its address records; try again shortly.
    private fun retryLater(info: NsdServiceInfo) {
        val attempt = synchronized(this) { (attempts[info.serviceName] ?: 0) + 1 }
        if (attempt > MAX_ATTEMPTS) return
        synchronized(this) { attempts[info.serviceName] = attempt }
        handler.postDelayed({ enqueue(info) }, attempt * 1_500L)
    }

    /** Returns false when the service resolved without enough detail to use, so it's worth retrying. */
    private fun onResolved(info: NsdServiceInfo): Boolean {
        val attributes = info.attributes
        fun attribute(key: String) = attributes[key]?.let { String(it, Charsets.UTF_8) }
        val id = attribute("id") ?: return false
        if (id == Store.deviceId || attribute("kind") != "mac") return true
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return false
        synchronized(this) {
            idsByServiceName[info.serviceName] = id
            attempts.remove(info.serviceName)
        }
        Ferry.macs.update { it + (id to MacService(id, attribute("name") ?: info.serviceName, host, info.port)) }
        return true
    }

    private companion object {
        const val TAG = "Ferry"
        const val MAX_ATTEMPTS = 4
    }
}
